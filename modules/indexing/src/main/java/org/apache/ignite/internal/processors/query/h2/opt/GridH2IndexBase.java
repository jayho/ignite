/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.h2.opt;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ignite.internal.processors.cache.distributed.dht.GridReservable;
import org.apache.ignite.internal.util.lang.GridFilteredIterator;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteBiPredicate;
import org.apache.ignite.spi.indexing.IndexingQueryFilter;
import org.h2.engine.Session;
import org.h2.index.BaseIndex;
import org.h2.index.ViewIndex;
import org.h2.message.DbException;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.table.TableFilter;
import org.jetbrains.annotations.Nullable;

import static org.apache.ignite.internal.processors.query.h2.opt.GridH2AbstractKeyValueRow.KEY_COL;
import static org.apache.ignite.internal.processors.query.h2.opt.GridH2AbstractKeyValueRow.VAL_COL;
import static org.apache.ignite.internal.processors.query.h2.opt.GridH2CollocationModel.buildCollocationModel;
import static org.apache.ignite.internal.processors.query.h2.opt.GridH2QueryType.LOCAL;
import static org.apache.ignite.internal.processors.query.h2.opt.GridH2QueryType.PREPARE;
import static org.apache.ignite.internal.processors.query.h2.opt.GridH2QueryType.REPLICATED;

/**
 * Index base.
 */
public abstract class GridH2IndexBase extends BaseIndex {
    /** */
    private static final AtomicLong idxIdGen = new AtomicLong();

    /** */
    protected final long idxId = idxIdGen.incrementAndGet();

    /** */
    private final ThreadLocal<Object> snapshot = new ThreadLocal<>();

    /** {@inheritDoc} */
    @Override public final void close(Session session) {
        // No-op. Actual index destruction must happen in method destroy.
    }

    /**
     * Attempts to destroys index and release all the resources.
     * We use this method instead of {@link #close(Session)} because that method
     * is used by H2 internally.
     */
    public abstract void destroy();

    /**
     * If the index supports rebuilding it has to creates its own copy.
     *
     * @return Rebuilt copy.
     * @throws InterruptedException If interrupted.
     */
    public GridH2IndexBase rebuild() throws InterruptedException {
        return this;
    }

    /**
     * Put row if absent.
     *
     * @param row Row.
     * @return Existing row or {@code null}.
     */
    public abstract GridH2Row put(GridH2Row row);

    /**
     * Remove row from index.
     *
     * @param row Row.
     * @return Removed row.
     */
    public abstract GridH2Row remove(SearchRow row);

    /**
     * Takes or sets existing snapshot to be used in current thread.
     *
     * @param s Optional existing snapshot to use.
     * @param qctx Query context.
     * @return Snapshot.
     */
    public final Object takeSnapshot(@Nullable Object s, GridH2QueryContext qctx) {
        assert snapshot.get() == null;

        if (s == null)
            s = doTakeSnapshot();

        if (s != null) {
            if (s instanceof GridReservable && !((GridReservable)s).reserve())
                return null;

            snapshot.set(s);

            if (qctx != null)
                qctx.putSnapshot(idxId, s);
        }

        return s;
    }

    /**
     * @param ses Session.
     */
    private static void clearViewIndexCache(Session ses) {
        Map<Object,ViewIndex> viewIndexCache = ses.getViewIndexCache(true);

        if (!viewIndexCache.isEmpty())
            viewIndexCache.clear();
    }

    /**
     * @param ses Session.
     * @param filters All joined table filters.
     * @param filter Current filter.
     * @return Multiplier.
     */
    public int getDistributedMultiplier(Session ses, TableFilter[] filters, int filter) {
        GridH2QueryContext qctx = GridH2QueryContext.get();

        // We do complex optimizations with respect to distributed joins only on prepare stage
        // because on run stage reordering of joined tables by Optimizer is explicitly disabled
        // and thus multiplier will be always the same, so it will not affect choice of index.
        // Query expressions can not be distributed as well.
        if (qctx == null || qctx.type() != PREPARE || !qctx.distributedJoins() || ses.isPreparingQueryExpression())
            return GridH2CollocationModel.MULTIPLIER_COLLOCATED;

        // We have to clear this cache because normally sub-query plan cost does not depend on anything
        // other than index condition masks and sort order, but in our case it can depend on order
        // of previous table filters.
        clearViewIndexCache(ses);

        assert filters != null;

        GridH2CollocationModel c = buildCollocationModel(qctx, ses.getSubQueryInfo(), filters, filter);

        return c.calculateMultiplier();
    }

    /** {@inheritDoc} */
    @Override public GridH2Table getTable() {
        return (GridH2Table)super.getTable();
    }

    /**
     * Takes and returns actual snapshot or {@code null} if snapshots are not supported.
     *
     * @return Snapshot or {@code null}.
     */
    @Nullable protected abstract Object doTakeSnapshot();

    /**
     * @return Thread local snapshot.
     */
    @SuppressWarnings("unchecked")
    protected <T> T threadLocalSnapshot() {
        return (T)snapshot.get();
    }

    /**
     * Releases snapshot for current thread.
     */
    public void releaseSnapshot() {
        Object s = snapshot.get();

        assert s != null;

        snapshot.remove();

        if (s instanceof GridReservable)
            ((GridReservable)s).release();

        if (s instanceof AutoCloseable)
            U.closeQuiet((AutoCloseable)s);
    }

    /**
     * Filters rows from expired ones and using predicate.
     *
     * @param iter Iterator over rows.
     * @param filter Optional filter.
     * @return Filtered iterator.
     */
    protected Iterator<GridH2Row> filter(Iterator<GridH2Row> iter, IndexingQueryFilter filter) {
        return new FilteringIterator(iter, U.currentTimeMillis(), filter, getTable().spaceName());
    }

    /**
     * @param tbl Table.
     * @param tblFilter Table filter.
     * @return Filter for currently running query or {@code null} if none.
     */
    protected static IndexingQueryFilter threadLocalFilter(GridH2Table tbl, TableFilter tblFilter) {
        GridH2QueryContext qctx = GridH2QueryContext.get();

        if (qctx != null)
            return qctx.filter();

        return null;
    }

    /** {@inheritDoc} */
    @Override public long getDiskSpaceUsed() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override public void checkRename() {
        throw DbException.getUnsupportedException("rename");
    }

    /** {@inheritDoc} */
    @Override public void add(Session ses, Row row) {
        throw DbException.getUnsupportedException("add");
    }

    /** {@inheritDoc} */
    @Override public void remove(Session ses, Row row) {
        throw DbException.getUnsupportedException("remove row");
    }

    /** {@inheritDoc} */
    @Override public void remove(Session ses) {
        throw DbException.getUnsupportedException("remove index");
    }

    /** {@inheritDoc} */
    @Override public void truncate(Session ses) {
        throw DbException.getUnsupportedException("truncate");
    }

    /** {@inheritDoc} */
    @Override public boolean needRebuild() {
        return false;
    }

    /**
     * Iterator which filters by expiration time and predicate.
     */
    protected static class FilteringIterator extends GridFilteredIterator<GridH2Row> {
        /** */
        private final IgniteBiPredicate<Object, Object> fltr;

        /** */
        private final long time;

        /** Is value required for filtering predicate? */
        private final boolean isValRequired;

        /**
         * @param iter Iterator.
         * @param time Time for expired rows filtering.
         * @param qryFilter Filter.
         * @param spaceName Space name.
         */
        protected FilteringIterator(Iterator<GridH2Row> iter, long time,
            IndexingQueryFilter qryFilter, String spaceName) {
            super(iter);

            this.time = time;

            if (qryFilter != null) {
                this.fltr = qryFilter.forSpace(spaceName);

                this.isValRequired = qryFilter.isValueRequired();
            } else {
                this.fltr = null;

                this.isValRequired = false;
            }
        }

        /**
         * @param row Row.
         * @return If this row was accepted.
         */
        @SuppressWarnings("unchecked")
        @Override protected boolean accept(GridH2Row row) {
            if (row instanceof GridH2AbstractKeyValueRow) {
                if (((GridH2AbstractKeyValueRow) row).expirationTime() <= time)
                    return false;
            }

            if (fltr == null)
                return true;

            Object key = row.getValue(KEY_COL).getObject();
            Object val = isValRequired ? row.getValue(VAL_COL).getObject() : null;

            assert key != null;
            assert !isValRequired || val != null;

            return fltr.apply(key, val);
        }
    }
}