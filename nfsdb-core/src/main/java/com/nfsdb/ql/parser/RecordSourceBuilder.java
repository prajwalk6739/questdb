/*******************************************************************************
 * _  _ ___ ___     _ _
 * | \| | __/ __| __| | |__
 * | .` | _|\__ \/ _` | '_ \
 * |_|\_|_| |___/\__,_|_.__/
 * <p>
 * Copyright (c) 2014-2015. The NFSdb project and its contributors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.nfsdb.ql.parser;

import com.nfsdb.JournalKey;
import com.nfsdb.collections.*;
import com.nfsdb.exceptions.JournalException;
import com.nfsdb.exceptions.NoSuchColumnException;
import com.nfsdb.factory.JournalReaderFactory;
import com.nfsdb.factory.configuration.JournalConfiguration;
import com.nfsdb.factory.configuration.JournalMetadata;
import com.nfsdb.factory.configuration.RecordColumnMetadata;
import com.nfsdb.io.sink.StringSink;
import com.nfsdb.ql.*;
import com.nfsdb.ql.impl.*;
import com.nfsdb.ql.model.*;
import com.nfsdb.ql.ops.*;
import com.nfsdb.ql.ops.fact.FunctionFactories;
import com.nfsdb.ql.ops.fact.FunctionFactory;
import com.nfsdb.storage.ColumnType;
import com.nfsdb.utils.Chars;
import com.nfsdb.utils.Interval;
import com.nfsdb.utils.Numbers;
import com.nfsdb.utils.Unsafe;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.util.ArrayDeque;

public class RecordSourceBuilder {

    private final static CharSequenceHashSet nullConstants = new CharSequenceHashSet();
    private final static NullConstant nullConstant = new NullConstant();
    private final ArrayDeque<VirtualColumn> stack = new ArrayDeque<>();
    private final IntrinsicExtractor intrinsicExtractor = new IntrinsicExtractor();
    private final VirtualColumnBuilder virtualColumnBuilderVisitor = new VirtualColumnBuilder();
    private final Signature mutableSig = new Signature();
    private final ObjList<VirtualColumn> mutableArgs = new ObjList<>();
    private final StringSink columnNameAssembly = new StringSink();
    private final int columnNamePrefixLen;
    private final ObjectPool<FlyweightCharSequence> csPool = new ObjectPool<>(FlyweightCharSequence.FACTORY, 64);
    private final CharSequenceIntHashMap joinMetadataIndexLookup = new CharSequenceIntHashMap();
    private final ObjectPool<ExprNode> exprNodePool = new ObjectPool<>(ExprNode.FACTORY, 16);
    private final IntHashSet deletedContexts = new IntHashSet();
    private final IntObjHashMap<ExprNode> preFilters = new IntObjHashMap<>();
    private final IntObjHashMap<ExprNode> postFilters = new IntObjHashMap<>();
    private final ObjList<JoinContext> joinClausesSwap1 = new ObjList<>();
    private final ObjList<JoinContext> joinClausesSwap2 = new ObjList<>();
    private final ObjectPool<JoinContext> contextPool = new ObjectPool<>(JoinContext.FACTORY, 16);
    private final PostOrderTreeTraversalAlgo traversalAlgo = new PostOrderTreeTraversalAlgo();
    private final IntList clausesToSteal = new IntList();
    private final IntList literalCollectorAIndexes = new IntList();
    private final ObjList<CharSequence> literalCollectorANames = new ObjList<>();
    private final IntList literalCollectorBIndexes = new IntList();
    private final ObjList<CharSequence> literalCollectorBNames = new ObjList<>();
    private final LiteralCollector literalCollector = new LiteralCollector();
    private final StringSink planSink = new StringSink();
    private final IntStack orderingStack = new IntStack();
    private final IntList tempCrosses = new IntList();
    private final IntList tempCrossIndexes = new IntList();
    private final IntHashSet constantConditions = new IntHashSet();
    private final IntHashSet postFilterRemoved = new IntHashSet();
    private final IntHashSet postFilterAvailable = new IntHashSet();
    private final ObjList<IntList> postFilterJournalRefs = new ObjList<>();
    private final ObjList<CharSequence> postFilterThrowawayNames = new ObjList<>();
    private final ObjectPool<IntList> intListPool = new ObjectPool<>(new ObjectPoolFactory<IntList>() {
        @Override
        public IntList newInstance() {
            return new IntList();
        }
    }, 16);
    private final ArrayDeque<ExprNode> andConditionStack = new ArrayDeque<>();
    private final IntList orderedJournals1 = new IntList();
    private final IntList orderedJournals2 = new IntList();
    private IntList orderedJournals;
    private ObjList<JoinContext> emittedJoinClauses;

    public RecordSourceBuilder() {
        // seed column name assembly with default column prefix, which we will reuse
        columnNameAssembly.put("col");
        columnNamePrefixLen = 3;
    }

    public RecordSource<? extends Record> compile(QueryModel model, JournalReaderFactory factory) throws JournalException, ParserException {
        return selectColumns(compile0(model, factory), model.getColumns());
    }

    public RecordSource<? extends Record> compileX(QueryModel model, JournalReaderFactory factory) throws JournalException, ParserException {

        ObjList<QueryModel> joinModels = model.getJoinModels();
        try {
            RecordSource<? extends Record> current = null;

            for (int i = 0, n = orderedJournals.size(); i < n; i++) {
                int index = orderedJournals.getQuick(i);
                QueryModel m = joinModels.getQuick(index);

                // pre-filters
                // this includes erasing "where" clause on top level query
                m.setWhereClause(preFilters.get(index));

                // compile
                RecordSource<? extends Record> rs = compile(m, factory);

                // check if this is the root of joins
                if (current == null) {
                    current = rs;
                } else {
                    // not the root, join to "current"
                    if (m.getJoinType() == QueryModel.JoinType.CROSS) {
                        // there are fields to analyse
                        current = new CrossJoinRecordSource(current, rs);
                    } else {
                        JoinContext jc = m.getContext();
                        RecordMetadata bm = current.getMetadata();
                        RecordMetadata am = rs.getMetadata();

                        ObjList<CharSequence> masterCols = null;
                        ObjList<CharSequence> slaveCols = null;

                        for (int k = 0, kn = jc.aIndexes.size(); k < kn; k++) {

                            CharSequence ca = jc.aNames.getQuick(k);
                            CharSequence cb = jc.bNames.getQuick(k);

                            if (am.getColumn(ca).getType() != bm.getColumn(cb).getType()) {
                                throw new ParserException(jc.aNodes.getQuick(k).position, "Column type mismatch");
                            }

                            if (masterCols == null) {
                                masterCols = new ObjList<>();
                            }

                            if (slaveCols == null) {
                                slaveCols = new ObjList<>();
                            }

                            masterCols.add(cb);
                            slaveCols.add(ca);
                        }
                        current = new HashJoinRecordSource(current, masterCols, rs, slaveCols, m.getJoinType() == QueryModel.JoinType.OUTER);
                    }
                }

                // check if there are post-filters
                ExprNode filter = postFilters.get(index);
                if (filter != null) {
                    current = new FilteredJournalRecordSource(current, createVirtualColumn(filter, current.getMetadata()));
                }

            }

            return current;
        } finally {
            clearState();
        }
    }

    public VirtualColumn createVirtualColumn(ExprNode node, RecordMetadata metadata) throws ParserException {
        virtualColumnBuilderVisitor.metadata = metadata;
        traversalAlgo.traverse(node, virtualColumnBuilderVisitor);
        return stack.poll();
    }

    public RecordSourceBuilder optimise(QueryModel parent, JournalReaderFactory factory) throws JournalException, ParserException {
        clearState();
        ObjList<QueryModel> joinModels = parent.getJoinModels();

        int n = joinModels.size();

        // create metadata for all journals and sub-queries involved in this query
        for (int i = 0; i < n; i++) {
            resolveJoinMetadata(parent, i, factory);
        }

        emittedJoinClauses = joinClausesSwap1;

        for (int i = 0; i < n; i++) {
            QueryModel m = joinModels.getQuick(i);
            processAndConditions(parent, m.getWhereClause());
            processAndConditions(parent, m.getJoinCriteria());
        }

        if (emittedJoinClauses.size() > 0) {
            processEmittedJoinClauses(parent);
        }

        trySwapJoinOrder(parent);
        tryAssignPostJoinFilters(parent);
        alignJoinFields(parent);
        return this;
    }

    public CharSequence plan(QueryModel parent) {
        ObjList<QueryModel> joinModels = parent.getJoinModels();
        planSink.clear();
        for (int i = 0, n = orderedJournals.size(); i < n; i++) {
            final int index = orderedJournals.getQuick(i);
            final QueryModel m = joinModels.getQuick(index);
            final JoinContext jc = m.getContext();

            final boolean cross = jc == null || jc.parents.size() == 0;
            planSink.put('+').put(' ').put(index);

            // join type
            planSink.put('[').put(' ');
            if (m.getJoinType() == QueryModel.JoinType.CROSS || cross) {
                planSink.put("cross");
            } else if (m.getJoinType() == QueryModel.JoinType.INNER) {
                planSink.put("inner");
            } else {
                planSink.put("outer");
            }
            planSink.put(' ').put(']').put(' ');

            // journal name/alias
            if (m.getAlias() != null) {
                planSink.put(m.getAlias());
            } else if (m.getJournalName() != null) {
                planSink.put(m.getJournalName().token);
            } else {
                planSink.put("subquery");
            }

            // pre-filter
            ExprNode filter = preFilters.get(index);
            if (filter != null) {
                planSink.put(" (filter: ");
                filter.toString(planSink);
                planSink.put(')');
            }

            // join clause
            if (!cross && jc.aIndexes.size() > 0) {
                planSink.put(" ON ");
                for (int k = 0, z = jc.aIndexes.size(); k < z; k++) {
                    if (k > 0) {
                        planSink.put(" and ");
                    }
                    jc.aNodes.getQuick(k).toString(planSink);
                    planSink.put(" = ");
                    jc.bNodes.getQuick(k).toString(planSink);
                }
            }

            // post-filter
            filter = postFilters.get(index);
            if (filter != null) {
                planSink.put(" (post-filter: ");
                filter.toString(planSink);
                planSink.put(')');
            }

            planSink.put('\n');
        }


        planSink.put('\n');

        return planSink;
    }

    private void addFilterOrEmitJoin(int idx, int ai, CharSequence an, ExprNode ao, int bi, CharSequence bn, ExprNode bo) {
        if (ai == bi && Chars.equals(an, bn)) {
            deletedContexts.add(idx);
            return;
        }

        if (ai == bi) {
            // (same journal)
            ExprNode node = exprNodePool.next().init(ExprNode.NodeType.OPERATION, "=", 0, 0);
            node.paramCount = 2;
            node.lhs = ao;
            node.rhs = bo;
            preFilters.put(ai, mergeFilters(preFilters.get(ai), node));
        } else {
            // (different journals)
            JoinContext jc = contextPool.next();
            jc.aIndexes.add(ai);
            jc.aNames.add(an);
            jc.aNodes.add(ao);
            jc.bIndexes.add(bi);
            jc.bNames.add(bn);
            jc.bNodes.add(bo);
            jc.slaveIndex = ai > bi ? ai : bi;
            jc.parents.add(ai < bi ? ai : bi);
            emittedJoinClauses.add(jc);
        }

        deletedContexts.add(idx);
    }

    private void addJoinContext(QueryModel parent, JoinContext context) {
        QueryModel jm = parent.getJoinModels().getQuick(context.slaveIndex);
        JoinContext other = jm.getContext();
        if (other == null) {
            jm.setContext(context);
        } else {
            jm.setContext(mergeContexts(parent, other, context));
        }
    }

    /**
     * Move fields that belong to slave journal to left and parent fields
     * to right of equals operator.
     */
    private void alignJoinFields(QueryModel parent) {
        ObjList<QueryModel> joinModels = parent.getJoinModels();
        for (int i = 0, n = orderedJournals.size(); i < n; i++) {
            JoinContext jc = joinModels.getQuick(orderedJournals.getQuick(i)).getContext();
            if (jc != null) {
                int index = jc.slaveIndex;
                for (int k = 0, kc = jc.aIndexes.size(); k < kc; k++) {
                    if (jc.aIndexes.getQuick(k) != index) {
                        int idx = jc.aIndexes.getQuick(k);
                        CharSequence name = jc.aNames.getQuick(k);
                        ExprNode node = jc.aNodes.getQuick(k);

                        jc.aIndexes.setQuick(k, jc.bIndexes.getQuick(k));
                        jc.aNames.setQuick(k, jc.bNames.getQuick(k));
                        jc.aNodes.setQuick(k, jc.bNodes.getQuick(k));

                        jc.bIndexes.setQuick(k, idx);
                        jc.bNames.setQuick(k, name);
                        jc.bNodes.setQuick(k, node);
                    }
                }
            }

        }
    }

    private void analyseEquals(QueryModel parent, ExprNode node) throws ParserException {
        literalCollectorAIndexes.clear();
        literalCollectorBIndexes.clear();

        literalCollectorANames.clear();
        literalCollectorBNames.clear();

        literalCollector.withParent(parent);
        traversalAlgo.traverse(node.lhs, literalCollector.lhs());
        traversalAlgo.traverse(node.rhs, literalCollector.rhs());

        int aSize = literalCollectorAIndexes.size();
        int bSize = literalCollectorBIndexes.size();

        JoinContext jc;

        switch (aSize) {
            case 0:
                if (bSize == 1 && literalCollector.nullCount == 0) {
                    // single journal reference
                    jc = contextPool.next();
                    jc.slaveIndex = literalCollectorBIndexes.getQuick(0);
                    preFilters.put(jc.slaveIndex, mergeFilters(preFilters.get(jc.slaveIndex), node));
                    addJoinContext(parent, jc);
                } else {
                    parent.addParsedWhereNode(node);
                }
                break;
            case 1:
                jc = contextPool.next();
                int lhi = literalCollectorAIndexes.getQuick(0);
                if (bSize == 1) {
                    int rhi = literalCollectorBIndexes.getQuick(0);
                    if (lhi == rhi) {
                        // single journal reference
                        jc.slaveIndex = lhi;
                        preFilters.put(lhi, mergeFilters(preFilters.get(lhi), node));
                    } else {
                        jc.aNodes.add(node.lhs);
                        jc.bNodes.add(node.rhs);
                        jc.aNames.add(literalCollectorANames.getQuick(0));
                        jc.bNames.add(literalCollectorBNames.getQuick(0));
                        jc.aIndexes.add(lhi);
                        jc.bIndexes.add(rhi);
                        int max = lhi > rhi ? lhi : rhi;
                        int min = lhi < rhi ? lhi : rhi;
                        jc.slaveIndex = max;
                        jc.parents.add(min);
                        linkDependencies(parent, min, max);
                    }
                    addJoinContext(parent, jc);
                } else if (literalCollector.nullCount == 0) {
                    // single journal reference
                    jc.slaveIndex = lhi;
                    preFilters.put(lhi, mergeFilters(preFilters.get(lhi), node));
                    addJoinContext(parent, jc);
                } else {
                    parent.addParsedWhereNode(node);
                }
                break;
            default:
                parent.addParsedWhereNode(node);
                break;
        }
    }

    @SuppressFBWarnings({"SF_SWITCH_NO_DEFAULT", "CC_CYCLOMATIC_COMPLEXITY"})
    private RecordSource<? extends Record> buildJournalRecordSource(QueryModel model, JournalReaderFactory factory) throws JournalException, ParserException {
        RecordMetadata metadata = model.getMetadata();
        JournalMetadata journalMetadata;

        if (metadata == null) {
            journalMetadata = collectJournalMetadata(model, factory);
        } else if (metadata instanceof JournalMetadata){
            journalMetadata = (JournalMetadata) metadata;
        } else {
            throw new ParserException(0, "Internal error: invalid metadata");
        }

        PartitionSource ps = new JournalPartitionSource(journalMetadata, true);
        RowSource rs = null;

        String latestByCol = null;
        RecordColumnMetadata latestByMetadata = null;
        ExprNode latestByNode = null;

        if (model.getLatestBy() != null) {
            latestByNode = model.getLatestBy();
            if (latestByNode.type != ExprNode.NodeType.LITERAL) {
                throw new ParserException(latestByNode.position, "Column name expected");
            }

            if (journalMetadata.invalidColumn(latestByNode.token)) {
                throw new InvalidColumnException(latestByNode.position);
            }

            latestByMetadata = journalMetadata.getColumn(latestByNode.token);

            ColumnType type = latestByMetadata.getType();
            if (type != ColumnType.SYMBOL && type != ColumnType.STRING) {
                throw new ParserException(latestByNode.position, "Expected symbol or string column, found: " + type);
            }

            if (!latestByMetadata.isIndexed()) {
                throw new ParserException(latestByNode.position, "Column is not indexed");
            }

            latestByCol = latestByNode.token;
        }

        ExprNode where = model.getWhereClause();
        if (where != null) {
            IntrinsicModel im = intrinsicExtractor.extract(where, journalMetadata, latestByCol);

            VirtualColumn filter = im.filter != null ? createVirtualColumn(im.filter, journalMetadata) : null;

            if (filter != null) {
                if (filter.getType() != ColumnType.BOOLEAN) {
                    throw new ParserException(im.filter.position, "Boolean expression expected");
                }

                if (filter.isConstant()) {
                    if (filter.getBool(null)) {
                        // constant TRUE, no filtering needed
                        filter = null;
                    } else {
                        im.intrinsicValue = IntrinsicValue.FALSE;
                    }
                }
            }

            if (im.intrinsicValue == IntrinsicValue.FALSE) {
                ps = new NoOpJournalPartitionSource(journalMetadata);
            } else {

                if (im.intervalHi < Long.MAX_VALUE || im.intervalLo > Long.MIN_VALUE) {

                    ps = new MultiIntervalPartitionSource(ps,
                            new SingleIntervalSource(
                                    new Interval(im.intervalLo, im.intervalHi
                                    )
                            )
                    );
                }

                if (im.intervalSource != null) {
                    ps = new MultiIntervalPartitionSource(ps, im.intervalSource);
                }

                if (latestByCol == null) {
                    if (im.keyColumn != null) {
                        switch (journalMetadata.getColumn(im.keyColumn).getType()) {
                            case SYMBOL:
                                rs = buildRowSourceForSym(im);
                                break;
                            case STRING:
                                rs = buildRowSourceForStr(im);
                                break;
                        }
                    }

                    if (filter != null) {
                        rs = new FilteredRowSource(rs == null ? new AllRowSource() : rs, filter);
                    }
                } else {
                    switch (latestByMetadata.getType()) {
                        case SYMBOL:
                            if (im.keyColumn != null) {
                                rs = new KvIndexSymListHeadRowSource(latestByCol, im.keyValues, filter);
                            } else {
                                rs = new KvIndexAllSymHeadRowSource(latestByCol, filter);
                            }
                            break;
                        case STRING:
                            if (im.keyColumn != null) {
                                rs = new KvIndexStrListHeadRowSource(latestByCol, im.keyValues, filter);
                            } else {
                                throw new ParserException(latestByNode.position, "Filter on string column expected");
                            }
                            break;
                    }
                }
            }
        } else if (latestByCol != null) {
            switch (latestByMetadata.getType()) {
                case SYMBOL:
                    rs = new KvIndexAllSymHeadRowSource(latestByCol, null);
                    break;
                case STRING:
                    throw new ParserException(latestByNode.position, "Filter on string column expected");
            }
        }

        return new JournalSource(ps, rs == null ? new AllRowSource() : rs);
    }

    private RowSource buildRowSourceForStr(IntrinsicModel im) {
        int nSrc = im.keyValues.size();
        switch (nSrc) {
            case 1:
                return new KvIndexStrLookupRowSource(im.keyColumn, new StrConstant(im.keyValues.getLast()));
            case 2:
                return new MergingRowSource(
                        new KvIndexStrLookupRowSource(im.keyColumn, new StrConstant(im.keyValues.get(0)), true),
                        new KvIndexStrLookupRowSource(im.keyColumn, new StrConstant(im.keyValues.get(1)), true)
                );
            default:
                RowSource sources[] = new RowSource[nSrc];
                for (int i = 0; i < nSrc; i++) {
                    Unsafe.arrayPut(sources, i, new KvIndexStrLookupRowSource(im.keyColumn, new StrConstant(im.keyValues.get(i)), true));
                }
                return new HeapMergingRowSource(sources);
        }
    }

    private RowSource buildRowSourceForSym(IntrinsicModel im) {
        int nSrc = im.keyValues.size();
        switch (nSrc) {
            case 1:
                return new KvIndexSymLookupRowSource(im.keyColumn, new StrConstant(im.keyValues.getLast()));
            case 2:
                return new MergingRowSource(
                        new KvIndexSymLookupRowSource(im.keyColumn, new StrConstant(im.keyValues.get(0)), true),
                        new KvIndexSymLookupRowSource(im.keyColumn, new StrConstant(im.keyValues.get(1)), true)
                );
            default:
                RowSource sources[] = new RowSource[nSrc];
                for (int i = 0; i < nSrc; i++) {
                    Unsafe.arrayPut(sources, i, new KvIndexSymLookupRowSource(im.keyColumn, new StrConstant(im.keyValues.get(i)), true));
                }
                return new HeapMergingRowSource(sources);
        }
    }

    private void clearState() {
        joinMetadataIndexLookup.clear();
        csPool.reset();
        exprNodePool.reset();
        contextPool.reset();
        preFilters.clear();
        postFilters.clear();
        constantConditions.clear();
        postFilterJournalRefs.clear();
        intListPool.reset();
    }

    private JournalMetadata collectJournalMetadata(QueryModel model, JournalReaderFactory factory) throws ParserException, JournalException {
        ExprNode readerNode = model.getJournalName();
        if (readerNode.type != ExprNode.NodeType.LITERAL && readerNode.type != ExprNode.NodeType.CONSTANT) {
            throw new ParserException(readerNode.position, "Journal name must be either literal or string constant");
        }

        JournalConfiguration configuration = factory.getConfiguration();

        String reader = Chars.stripQuotes(readerNode.token);
        if (configuration.exists(reader) == JournalConfiguration.JournalExistenceCheck.DOES_NOT_EXIST) {
            throw new ParserException(readerNode.position, "Journal does not exist");
        }

        if (configuration.exists(reader) == JournalConfiguration.JournalExistenceCheck.EXISTS_FOREIGN) {
            throw new ParserException(readerNode.position, "Journal directory is of unknown format");
        }

        return factory.getOrCreateMetadata(new JournalKey<>(reader));
    }

    private RecordSource<? extends Record> compile0(QueryModel model, JournalReaderFactory factory) throws JournalException, ParserException {
        if (model.getJournalName() != null) {
            return buildJournalRecordSource(model, factory);
        } else {
            RecordSource<? extends Record> rs = compile(model.getNestedModel(), factory);
            if (model.getWhereClause() == null) {
                return rs;
            }

            RecordMetadata m = rs.getMetadata();
            IntrinsicModel im = intrinsicExtractor.extract(model.getWhereClause(), m, null);

            switch (im.intrinsicValue) {
                case FALSE:
                    return new NoOpJournalRecordSource(rs);
                default:
                    if (im.intervalSource != null) {
                        rs = new IntervalJournalRecordSource(rs, im.intervalSource);
                    }
                    if (im.filter != null) {
                        VirtualColumn vc = createVirtualColumn(im.filter, m);
                        if (vc.isConstant()) {
                            if (vc.getBool(null)) {
                                return rs;
                            } else {
                                return new NoOpJournalRecordSource(rs);
                            }
                        }
                        return new FilteredJournalRecordSource(rs, vc);
                    } else {
                        return rs;
                    }
            }
        }
    }

    private void createColumn(ExprNode node, RecordMetadata metadata) throws ParserException {
        mutableArgs.clear();
        mutableSig.clear();

        int argCount = node.paramCount;
        switch (argCount) {
            case 0:
                switch (node.type) {
                    case LITERAL:
                        // lookup column
                        stack.push(lookupColumn(node, metadata));
                        break;
                    case CONSTANT:
                        stack.push(parseConstant(node));
                        break;
                    default:
                        // lookup zero arg function from symbol table
                        stack.push(lookupFunction(node, mutableSig.setName(node.token).setParamCount(0), null));
                }
                break;
            default:
                mutableArgs.ensureCapacity(argCount);
                mutableSig.setName(node.token).setParamCount(argCount);
                for (int n = 0; n < argCount; n++) {
                    VirtualColumn c = stack.poll();
                    if (c == null) {
                        throw new ParserException(node.position, "Too few arguments");
                    }
                    mutableSig.paramType(n, c.getType(), c.isConstant());
                    mutableArgs.setQuick(n, c);
                }
                stack.push(lookupFunction(node, mutableSig, mutableArgs));
        }
    }

    private CharSequence extractColumnName(CharSequence token, int dot) {
        return dot == -1 ? token : csPool.next().of(token, dot + 1, token.length() - dot - 1);
    }

    private void linkDependencies(QueryModel model, int parent, int child) {
        model.getJoinModels().getQuick(parent).addDependency(child);
    }

    @SuppressFBWarnings({"LEST_LOST_EXCEPTION_STACK_TRACE"})
    private VirtualColumn lookupColumn(ExprNode node, RecordMetadata metadata) throws ParserException {
        try {
            int index = metadata.getColumnIndex(node.token);
            switch (metadata.getColumn(index).getType()) {
                case DOUBLE:
                    return new DoubleRecordSourceColumn(index);
                case INT:
                    return new IntRecordSourceColumn(index);
                case LONG:
                    return new LongRecordSourceColumn(index);
                case STRING:
                    return new StrRecordSourceColumn(index);
                case SYMBOL:
                    return new SymRecordSourceColumn(index);
                default:
                    throw new ParserException(node.position, "Not yet supported type");
            }
        } catch (NoSuchColumnException e) {
            throw new InvalidColumnException(node.position);
        }
    }

    private VirtualColumn lookupFunction(ExprNode node, Signature sig, ObjList<VirtualColumn> args) throws ParserException {
        FunctionFactory factory = FunctionFactories.find(sig, args);
        if (factory == null) {
            throw new ParserException(node.position, "No such function: " + sig.userReadable());
        }

        Function f = factory.newInstance(args);
        if (args != null) {
            int n = node.paramCount;
            for (int i = 0; i < n; i++) {
                f.setArg(i, args.getQuick(i));
            }
        }
        return f.isConstant() ? processConstantExpression(f) : f;
    }

    private JoinContext mergeContexts(QueryModel parent, JoinContext a, JoinContext b) {
        assert a.slaveIndex == b.slaveIndex;

        deletedContexts.clear();
        JoinContext r = contextPool.next();
        // check if we merging a.x = b.x to a.y = b.y
        // or a.x = b.x to a.x = b.y, e.g. one of columns in the same
        for (int i = 0, n = b.aNames.size(); i < n; i++) {

            CharSequence ban = b.aNames.getQuick(i);
            int bai = b.aIndexes.getQuick(i);
            ExprNode bao = b.aNodes.getQuick(i);

            CharSequence bbn = b.bNames.getQuick(i);
            int bbi = b.bIndexes.getQuick(i);
            ExprNode bbo = b.bNodes.getQuick(i);

            for (int k = 0, z = a.aNames.size(); k < z; k++) {

                if (deletedContexts.contains(k)) {
                    continue;
                }

                CharSequence aan = a.aNames.getQuick(k);
                int aai = a.aIndexes.getQuick(k);
                ExprNode aao = a.aNodes.getQuick(k);
                CharSequence abn = a.bNames.getQuick(k);
                int abi = a.bIndexes.getQuick(k);
                ExprNode abo = a.bNodes.getQuick(k);

                if (aai == bai && Chars.equals(aan, ban)) {
                    // a.x = ?.x
                    //  |     ?
                    // a.x = ?.y
                    addFilterOrEmitJoin(k, abi, abn, abo, bbi, bbn, bbo);
                    break;
                } else if (abi == bai && Chars.equals(abn, ban)) {
                    // a.y = b.x
                    //    /
                    // b.x = a.x
                    addFilterOrEmitJoin(k, aai, aan, aao, bbi, bbn, bbo);
                    break;
                } else if (aai == bbi && Chars.equals(aan, bbn)) {
                    // a.x = b.x
                    //     \
                    // b.y = a.x
                    addFilterOrEmitJoin(k, abi, abn, abo, bai, ban, bao);
                    break;
                } else if (abi == bbi && Chars.equals(abn, bbn)) {
                    // a.x = b.x
                    //        |
                    // a.y = b.x
                    addFilterOrEmitJoin(k, aai, aan, aao, bai, ban, bao);
                    break;
                }
            }
            r.aIndexes.add(bai);
            r.aNames.add(ban);
            r.aNodes.add(bao);
            r.bIndexes.add(bbi);
            r.bNames.add(bbn);
            r.bNodes.add(bbo);
            int max = bai > bbi ? bai : bbi;
            int min = bai < bbi ? bai : bbi;
            r.slaveIndex = max;
            r.parents.add(min);
            linkDependencies(parent, min, max);
        }

        // add remaining a nodes
        for (int i = 0, n = a.aNames.size(); i < n; i++) {
            int aai, abi, min, max;

            aai = a.aIndexes.getQuick(i);
            abi = a.bIndexes.getQuick(i);

            if (aai < abi) {
                min = aai;
                max = abi;
            } else {
                min = abi;
                max = aai;
            }

            if (deletedContexts.contains(i)) {
                if (!r.parents.contains(min)) {
                    unlinkDependencies(parent, min, max);
                }
            } else {
                r.aNames.add(a.aNames.getQuick(i));
                r.bNames.add(a.bNames.getQuick(i));
                r.aIndexes.add(aai);
                r.bIndexes.add(abi);
                r.aNodes.add(a.aNodes.getQuick(i));
                r.bNodes.add(a.bNodes.getQuick(i));

                r.parents.add(min);
                linkDependencies(parent, min, max);
            }
        }

        return r;
    }

    private ExprNode mergeFilters(ExprNode a, ExprNode b) {
        if (a == null && b == null) {
            return null;
        }

        if (a == null) {
            return b;
        }

        if (b == null) {
            return a;
        }

        ExprNode n = exprNodePool.next().init(ExprNode.NodeType.OPERATION, "and", 0, 0);
        n.paramCount = 2;
        n.lhs = a;
        n.rhs = b;
        return n;
    }

    private JoinContext moveClauses(QueryModel parent, JoinContext from, JoinContext to, IntList positions) {
        int p = 0;
        int m = positions.size();

        if (m == 0) {
            return from;
        }

        JoinContext result = contextPool.next();
        result.slaveIndex = from.slaveIndex;

        for (int i = 0, n = from.aIndexes.size(); i < n; i++) {
            // logically those clauses we move away from "from" context
            // should not longer exist in "from", but instead of implementing
            // "delete" function, which would be manipulating underlying array
            // on every invocation, we copy retained clauses to new context,
            // which is "result".
            // hence whenever exists in "positions" we copy clause to "to"
            // otherwise copy to "result"
            JoinContext t = p < m && i == positions.getQuick(p) ? to : result;
            int ai = from.aIndexes.getQuick(i);
            int bi = from.bIndexes.getQuick(i);
            t.aIndexes.add(ai);
            t.aNames.add(from.aNames.getQuick(i));
            t.aNodes.add(from.aNodes.getQuick(i));
            t.bIndexes.add(bi);
            t.bNames.add(from.bNames.getQuick(i));
            t.bNodes.add(from.bNodes.getQuick(i));

            // either ai or bi is definitely belongs to this context
            if (ai != t.slaveIndex) {
                t.parents.add(ai);
                linkDependencies(parent, ai, bi);
            } else {
                t.parents.add(bi);
                linkDependencies(parent, bi, ai);
            }
        }

        return result;
    }

    private int orderJournals(QueryModel parent, IntList ordered) {
        ordered.clear();
        this.orderingStack.clear();
        ObjList<QueryModel> joinModels = parent.getJoinModels();

        int cost = 0;

        for (int i = 0, n = joinModels.size(); i < n; i++) {
            QueryModel q = joinModels.getQuick(i);
            if (q.isCrossJoin() || 0 == q.getContext().parents.size()) {
                if (q.getDependencies().size() > 0) {
                    orderingStack.push(i);
                } else {
                    tempCrossIndexes.add(i);
                }
            } else {
                q.getContext().inCount = q.getContext().parents.size();
            }
        }

        while (orderingStack.notEmpty()) {
            //remove a node n from orderingStack
            int index = orderingStack.pop();

            //insert n into orderedJournals
            ordered.add(index);

            QueryModel m = joinModels.getQuick(index);

            switch (m.getJoinType()) {
                case CROSS:
                    cost += 10;
                    break;
                default:
                    cost += 5;
            }

            IntHashSet dependencies = m.getDependencies();

            //for each node m with an edge e from n to m do
            for (int i = 0, k = dependencies.size(); i < k; i++) {
                int depIndex = dependencies.get(i);
                JoinContext jc = joinModels.getQuick(depIndex).getContext();
                if (--jc.inCount == 0) {
                    orderingStack.push(depIndex);
                }
            }
        }

        //Check to see if all edges are removed
        for (int i = 0, n = joinModels.size(); i < n; i++) {
            QueryModel m = joinModels.getQuick(i);
            if (!m.isCrossJoin() && m.getContext().inCount > 0) {
                return Integer.MAX_VALUE;
            }
        }

        // add pure crosses at end of ordered journal list
        for (int i = 0, n = tempCrossIndexes.size(); i < n; i++) {
            ordered.add(tempCrossIndexes.getQuick(i));
        }

        return cost;
    }

    @SuppressFBWarnings({"ES_COMPARING_STRINGS_WITH_EQ"})
    private VirtualColumn parseConstant(ExprNode node) throws ParserException {

        if ("null".equals(node.token)) {
            return nullConstant;
        }

        String s = Chars.stripQuotes(node.token);

        // by ref comparison
        //noinspection StringEquality
        if (s != node.token) {
            return new StrConstant(s);
        }

        try {
            return new IntConstant(Numbers.parseInt(node.token));
        } catch (NumberFormatException ignore) {

        }

        try {
            return new LongConstant(Numbers.parseLong(node.token));
        } catch (NumberFormatException ignore) {

        }

        try {
            return new DoubleConstant(Numbers.parseDouble(node.token));
        } catch (NumberFormatException ignore) {

        }

        throw new ParserException(node.position, "Unknown value type: " + node.token);
    }

    /**
     * Splits "where" clauses into "and" concatenated list of boolean expressions.
     *
     * @param node expression node
     * @throws ParserException
     */
    private void processAndConditions(QueryModel parent, ExprNode node) throws ParserException {
        // pre-order traversal
        andConditionStack.clear();
        while (!andConditionStack.isEmpty() || node != null) {
            if (node != null) {
                switch (node.token) {
                    case "and":
                        if (node.rhs != null) {
                            andConditionStack.push(node.rhs);
                        }
                        node = node.lhs;
                        break;
                    case "=":
                        analyseEquals(parent, node);
                        node = null;
                        break;
                    case "or":
                        processOrConditions(parent, node);
                        node = null;
                        break;
                    default:
                        parent.addParsedWhereNode(node);
                        node = null;
                        break;
                }
            } else {
                node = andConditionStack.poll();
            }
        }
    }

    private VirtualColumn processConstantExpression(Function f) {
        switch (f.getType()) {
            case INT:
                return new IntConstant(f.getInt(null));
            case DOUBLE:
                return new DoubleConstant(f.getDouble(null));
            case BOOLEAN:
                return new BooleanConstant(f.getBool(null));
            default:
                return f;
        }
    }

    private void processEmittedJoinClauses(QueryModel model) {
        // process emitted join conditions
        do {
            ObjList<JoinContext> clauses = emittedJoinClauses;

            if (clauses == joinClausesSwap1) {
                emittedJoinClauses = joinClausesSwap2;
            } else {
                emittedJoinClauses = joinClausesSwap1;
            }
            emittedJoinClauses.clear();
            for (int i = 0, k = clauses.size(); i < k; i++) {
                addJoinContext(model, clauses.getQuick(i));
            }
        } while (emittedJoinClauses.size() > 0);

    }

    /**
     * There are two ways "or" conditions can go:
     * - all "or" conditions have at least one fields in common
     * e.g. a.x = b.x or a.x = b.y
     * this can be implemented as a hash join where master table is "b"
     * and slave table is "a" keyed on "a.x" so that
     * if HashTable contains all rows of "a" keyed on "a.x"
     * hash join algorithm can do:
     * rows = HashTable.get(b.x);
     * if (rows == null) {
     * rows = HashTable.get(b.y);
     * }
     * <p>
     * in this case tables can be reordered as long as "b" is processed
     * before "a"
     * <p>
     * - second possibility is where all "or" conditions are random
     * in which case query like this:
     * <p>
     * from a
     * join c on a.x = c.x
     * join b on a.x = b.x or c.y = b.y
     * <p>
     * can be rewritten to:
     * <p>
     * from a
     * join c on a.x = c.x
     * join b on a.x = b.x
     * union
     * from a
     * join c on a.x = c.x
     * join b on c.y = b.y
     */
    private void processOrConditions(QueryModel parent, ExprNode node) {
        // stub: use filter
        parent.addParsedWhereNode(node);
    }

    private void resolveJoinMetadata(QueryModel parent, int index, JournalReaderFactory factory) throws JournalException, ParserException {
        QueryModel model = parent.getJoinModels().getQuick(index);
        if (model.getJournalName() != null) {
            model.setMetadata(collectJournalMetadata(model, factory));
        } else {
            RecordSource<? extends Record> rs = compile(model, factory);
            model.setMetadata(rs.getMetadata());
            model.setRecordSource(rs);
        }


        if (model.getAlias() != null) {
            joinMetadataIndexLookup.put(model.getAlias(), index);
        } else if (model.getJournalName() != null) {
            joinMetadataIndexLookup.put(model.getJournalName().token, index);
        }
        //todo: check what happens when subquery does not have alias
    }

    private int resolveJournalIndex(QueryModel parent, CharSequence alias, CharSequence column, int position) throws ParserException {
        ObjList<QueryModel> joinModels = parent.getJoinModels();
        int index = -1;
        if (alias == null) {
            for (int i = 0, n = joinModels.size(); i < n; i++) {
                RecordMetadata m = joinModels.getQuick(i).getMetadata();
                if (m.invalidColumn(column)) {
                    continue;
                }

                if (index > -1) {
                    throw new ParserException(position, "Ambiguous column name");
                }

                index = i;
            }

            if (index == -1) {
                throw new InvalidColumnException(position);
            }

            return index;
        } else {
            index = joinMetadataIndexLookup.get(alias);

            if (index == -1) {
                throw new ParserException(position, "Invalid journal name/alias");
            }
            RecordMetadata m = joinModels.getQuick(index).getMetadata();

            if (m.invalidColumn(column)) {
                throw new InvalidColumnException(position);
            }

            return index;
        }
    }

    private RecordSource<? extends Record> selectColumns(RecordSource<? extends Record> rs, ObjList<QueryColumn> columns) throws ParserException {
        if (columns.size() == 0) {
            return rs;
        }

        ObjList<VirtualColumn> virtualColumns = null;
        ObjList<String> selectedColumns = new ObjList<>();
        int columnSequence = 0;
        final RecordMetadata meta = rs.getMetadata();

        // create virtual columns from select list
        for (int i = 0, k = columns.size(); i < k; i++) {
            QueryColumn qc = columns.getQuick(i);
            ExprNode node = qc.getAst();

            switch (node.type) {
                case LITERAL:
                    if (meta.invalidColumn(node.token)) {
                        throw new InvalidColumnException(node.position);
                    }
                    selectedColumns.add(node.token);
                    break;
                default:

                    String colName = qc.getName();
                    if (colName == null) {
                        columnNameAssembly.clear(columnNamePrefixLen);
                        Numbers.append(columnNameAssembly, columnSequence++);
                        colName = columnNameAssembly.toString();
                    }
                    VirtualColumn c = createVirtualColumn(qc.getAst(), rs.getMetadata());
                    c.setName(colName);
                    selectedColumns.add(colName);
                    if (virtualColumns == null) {
                        virtualColumns = new ObjList<>();
                    }
                    virtualColumns.add(c);
            }
        }

        if (virtualColumns != null) {
            rs = new VirtualColumnRecordSource(rs, virtualColumns);
        }
        return new SelectedColumnsRecordSource(rs, selectedColumns);
    }

    /**
     * Moves reversible join clauses, such as a.x = b.x from journal "from" to journal "to".
     *
     * @param to   target journal index
     * @param from source journal index
     * @param jc   context of target journal index
     * @return false if "from" is outer joined journal, otherwise - true
     */
    private boolean swapJoinOrder(QueryModel parent, int to, int from, JoinContext jc) {
        ObjList<QueryModel> joinModels = parent.getJoinModels();
        QueryModel jm = joinModels.getQuick(from);
        if (jm.getJoinType() == QueryModel.JoinType.OUTER) {
            return false;
        }

        clausesToSteal.clear();

        JoinContext that = jm.getContext();
        if (that != null && that.parents.contains(to)) {
            int zc = that.aIndexes.size();
            for (int z = 0; z < zc; z++) {
                if (that.aIndexes.getQuick(z) == to || that.bIndexes.getQuick(z) == to) {
                    clausesToSteal.add(z);
                }
            }

            if (clausesToSteal.size() < zc) {
                QueryModel target = joinModels.getQuick(to);
                target.getDependencies().clear();
                if (jc == null) {
                    target.setContext(jc = contextPool.next());
                }
                jc.slaveIndex = to;
                jm.setContext(moveClauses(parent, that, jc, clausesToSteal));
                if (target.getJoinType() == QueryModel.JoinType.CROSS) {
                    target.setJoinType(QueryModel.JoinType.INNER);
                }
            }
        }
        return true;
    }

    private void tryAssignPostJoinFilters(QueryModel parent) throws ParserException {

        postFilterAvailable.clear();
        postFilterRemoved.clear();

        literalCollector.withParent(parent);
        ObjList<ExprNode> filterNodes = parent.getParsedWhere();
        // collect journal indexes from each part of global filter
        int pc = filterNodes.size();
        for (int i = 0; i < pc; i++) {
            IntList indexes = intListPool.next();
            traversalAlgo.traverse(filterNodes.getQuick(i), literalCollector.to(indexes, postFilterThrowawayNames));
            postFilterJournalRefs.add(indexes);
            postFilterThrowawayNames.clear();
        }

        // match journal references to set of journals in join order
        for (int i = 0, n = orderedJournals.size(); i < n; i++) {
            postFilterAvailable.add(orderedJournals.getQuick(i));

            for (int k = 0; k < pc; k++) {
                if (postFilterRemoved.contains(k)) {
                    continue;
                }

                IntList refs = postFilterJournalRefs.getQuick(k);
                int rs = refs.size();
                if (rs == 0) {
                    // condition has no journal references
                    // must evaluate as constant
                    postFilterRemoved.add(k);
                    constantConditions.add(k);
                } else {
                    boolean remove = true;
                    for (int y = 0; y < rs; y++) {
                        if (!postFilterAvailable.contains(refs.getQuick(y))) {
                            remove = false;
                            break;
                        }
                    }
                    if (remove) {
                        postFilterRemoved.add(k);
                        postFilters.put(i, filterNodes.getQuick(k));
                    }
                }
            }
        }

        assert postFilterRemoved.size() == pc;
    }

    /**
     * Identify joined journals without join clause and try to find other reversible join clauses
     * that may be applied to it. For example when these journals joined"
     * <p>
     * from a
     * join b on c.x = b.x
     * join c on c.y = a.y
     * <p>
     * the system that prefers child table with lowest index will attribute c.x = b.x clause to
     * journal "c" leaving "b" without clauses.
     */
    @SuppressWarnings({"StatementWithEmptyBody", "ConstantConditions"})
    private void trySwapJoinOrder(QueryModel parent) throws ParserException {
        ObjList<QueryModel> joinModels = parent.getJoinModels();
        int n = joinModels.size();

        tempCrosses.clear();
        // collect crosses
        for (int i = 0; i < n; i++) {
            QueryModel q = joinModels.getQuick(i);
            if (q.isCrossJoin()) {
                tempCrosses.add(i);
            }
        }

        int cost = Integer.MAX_VALUE;
        int root = -1;

        // analyse state of tree for each set of n-1 crosses
        for (int z = 0, zc = tempCrosses.size(); z < zc; z++) {
            for (int i = 0; i < zc; i++) {
                if (z != i) {
                    int to = tempCrosses.getQuick(i);
                    JoinContext jc = joinModels.getQuick(to).getContext();
                    // look above i up to OUTER join
                    for (int k = i - 1; k > -1 && swapJoinOrder(parent, to, k, jc); k--) ;
                    // look below i for up to OUTER join
                    for (int k = i + 1; k < n && swapJoinOrder(parent, to, k, jc); k++) ;
                }
            }

            IntList ordered;
            if (orderedJournals == orderedJournals1) {
                ordered = orderedJournals2;
            } else {
                ordered = orderedJournals1;
            }

            ordered.clear();
            int thisCost = orderJournals(parent, ordered);
            if (thisCost < cost) {
                root = z;
                cost = thisCost;
                orderedJournals = ordered;
            }
        }

        if (root == -1) {
            throw new ParserException(0, "Cycle");
        }
    }

    private void unlinkDependencies(QueryModel model, int parent, int child) {
        model.getJoinModels().getQuick(parent).removeDependency(child);
    }

    private class VirtualColumnBuilder implements Visitor {
        private RecordMetadata metadata;

        @Override
        public void visit(ExprNode node) throws ParserException {
            createColumn(node, metadata);
        }
    }

    private class LiteralCollector implements Visitor {
        private IntList indexes;
        private ObjList<CharSequence> names;
        private int nullCount;
        private QueryModel parent;

        private Visitor lhs() {
            indexes = literalCollectorAIndexes;
            names = literalCollectorANames;
            return this;
        }

        private Visitor rhs() {
            indexes = literalCollectorBIndexes;
            names = literalCollectorBNames;
            return this;
        }

        private Visitor to(IntList indexes, ObjList<CharSequence> names) {
            this.indexes = indexes;
            this.names = names;
            return this;
        }

        private void withParent(QueryModel parent) {
            this.parent = parent;
        }

        @Override
        public void visit(ExprNode node) throws ParserException {
            switch (node.type) {
                case LITERAL:
                    int dot = node.token.indexOf('.');
                    CharSequence name = extractColumnName(node.token, dot);
                    indexes.add(resolveJournalIndex(parent, dot == -1 ? null : csPool.next().of(node.token, 0, dot), name, node.position));
                    names.add(name);
                    break;
                case CONSTANT:
                    if (nullConstants.contains(node.token)) {
                        nullCount++;
                    }
                    break;
            }
        }
    }

    static {
        nullConstants.add("null");
        nullConstants.add("NaN");
    }
}