/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package herddb.sql;

import com.google.common.collect.ImmutableList;
import herddb.core.AbstractTableManager;
import herddb.core.DBManager;
import herddb.core.TableSpaceManager;
import herddb.metadata.MetadataStorageManagerException;
import herddb.model.Column;
import herddb.model.ColumnTypes;
import herddb.model.ExecutionPlan;
import herddb.model.Predicate;
import herddb.model.RecordFunction;
import herddb.model.StatementExecutionException;
import herddb.model.Table;
import herddb.model.commands.DeleteStatement;
import herddb.model.commands.SQLPlannedOperationStatement;
import herddb.model.commands.ScanStatement;
import herddb.model.commands.UpdateStatement;
import herddb.model.planner.AggregateOp;
import herddb.model.planner.DeleteOp;
import herddb.model.planner.FilterOp;
import herddb.model.planner.FilteredTableScanOp;
import herddb.model.planner.InsertOp;
import herddb.model.planner.LimitOp;
import herddb.model.planner.PlannerOp;
import herddb.model.planner.ProjectOp;
import herddb.model.planner.SortOp;
import herddb.model.planner.TableScanOp;
import herddb.model.planner.UpdateOp;
import herddb.model.planner.ValuesOp;
import herddb.sql.expressions.CompiledSQLExpression;
import herddb.sql.expressions.SQLExpressionCompiler;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import net.sf.jsqlparser.statement.Statement;
import org.apache.calcite.DataContext;
import org.apache.calcite.adapter.enumerable.EnumerableAggregate;
import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableFilter;
import org.apache.calcite.adapter.enumerable.EnumerableInterpreter;
import org.apache.calcite.adapter.enumerable.EnumerableLimit;
import org.apache.calcite.adapter.enumerable.EnumerableProject;
import org.apache.calcite.adapter.enumerable.EnumerableSort;
import org.apache.calcite.adapter.enumerable.EnumerableTableModify;
import org.apache.calcite.adapter.enumerable.EnumerableTableScan;
import org.apache.calcite.adapter.enumerable.EnumerableValues;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.QueryProvider;
import org.apache.calcite.linq4j.Queryable;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.prepare.Prepare;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.TableModify;
import org.apache.calcite.rel.logical.LogicalTableModify;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.ModifiableTable;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlExplainFormat;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.Programs;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;
import org.apache.calcite.util.ImmutableBitSet;

/**
 * SQL Planner based upon Apache Calcite
 *
 * @author eolivelli
 */
public class CalcitePlanner implements AbstractSQLPlanner {

    private final DBManager manager;
    private final AbstractSQLPlanner fallback;

    public CalcitePlanner(DBManager manager) {
        this.manager = manager;
        this.fallback = new SQLPlanner(manager, 1024 * 1024);
    }

    @Override
    public void clearCache() {
        fallback.clearCache();
    }

    @Override
    public long getCacheHits() {
        return 0;
    }

    @Override
    public long getCacheMisses() {
        return 0;
    }

    @Override
    public long getCacheSize() {
        return 0;
    }

    @Override
    public ExecutionPlan plan(String defaultTableSpace, Statement stmt, boolean scan, boolean returnValues, int maxRows) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public TranslatedQuery translate(String defaultTableSpace, String query, List<Object> parameters, boolean scan, boolean allowCache, boolean returnValues, int maxRows) throws StatementExecutionException {
        if (query.startsWith("CREATE")
                || query.startsWith("DROP")
                || query.startsWith("EXECUTE")
                || query.startsWith("ALTER")
                || query.startsWith("BEGIN")
                || query.startsWith("COMMIT")
                || query.startsWith("ROLLBACK")
                || query.startsWith("TRUNCATE")) {
            return fallback.translate(defaultTableSpace, query, parameters, scan, allowCache, returnValues, maxRows);
        }
        try {
            SchemaPlus schema = getRootSchema();
            List<RelTraitDef> traitDefs = new ArrayList<>();
            traitDefs.add(ConventionTraitDef.INSTANCE);
            SqlParser.Config parserConfig
                    = SqlParser.configBuilder(SqlParser.Config.DEFAULT)
                            .setCaseSensitive(false)
                            .setConformance(SqlConformanceEnum.MYSQL_5)
                            .build();

            final FrameworkConfig config = Frameworks.newConfigBuilder()
                    .parserConfig(parserConfig)
                    .defaultSchema(schema.getSubSchema(defaultTableSpace))
                    .traitDefs(traitDefs)
                    // define the rules you want to apply

                    .programs(Programs.ofRules(Programs.RULE_SET))
                    .build();
            RelNode plan = runPlanner(config, query);
            ExecutionPlan executionPlan = ExecutionPlan.simple(
                    new SQLPlannedOperationStatement(
                            convertRelNode(plan, returnValues)
                                    .optimize())
            );
            return new TranslatedQuery(executionPlan, new SQLStatementEvaluationContext(query, parameters));
        } catch (MetadataStorageManagerException | RelConversionException
                | SqlParseException | ValidationException ex) {
            throw new StatementExecutionException(ex);
        }
    }

    private RelNode runPlanner(FrameworkConfig config, String query) throws RelConversionException, SqlParseException, ValidationException {
        Planner planner = Frameworks.getPlanner(config);
        System.out.println("Query:" + query);
        SqlNode n = planner.parse(query);
        n = planner.validate(n);
        RelNode root = planner.rel(n).project();
        System.out.println(RelOptUtil.dumpPlan("-- Logical Plan", root, SqlExplainFormat.TEXT,
                SqlExplainLevel.DIGEST_ATTRIBUTES));
        RelOptCluster cluster = root.getCluster();
        final RelOptPlanner optPlanner = cluster.getPlanner();
        RelTraitSet desiredTraits
                = cluster.traitSet().replace(EnumerableConvention.INSTANCE);
        final RelNode newRoot = optPlanner.changeTraits(root, desiredTraits);
        optPlanner.setRoot(newRoot);
        RelNode bestExp = optPlanner.findBestExp();
        System.out.println(RelOptUtil.dumpPlan("-- Best Plan", bestExp, SqlExplainFormat.TEXT,
                SqlExplainLevel.DIGEST_ATTRIBUTES));
        return bestExp;
    }

    private SchemaPlus getRootSchema() throws MetadataStorageManagerException {
        final SchemaPlus rootSchema = Frameworks.createRootSchema(true);
        for (String tableSpace : manager.getLocalTableSpaces()) {
            TableSpaceManager tableSpaceManager = manager.getTableSpaceManager(tableSpace);
            SchemaPlus schema = rootSchema.add(tableSpace, new AbstractSchema());
            List<Table> tables = tableSpaceManager.getAllTablesForPlanner();
            for (Table table : tables) {
                AbstractTableManager tableManager = tableSpaceManager.getTableManager(table.name);
                TableImpl tableDef = new TableImpl(tableManager);
                schema.add(table.name, tableDef);
            }
        }
        return rootSchema;
    }

    private PlannerOp convertRelNode(RelNode plan, boolean returnValues) throws StatementExecutionException {
        if (plan instanceof EnumerableTableModify) {
            EnumerableTableModify dml = (EnumerableTableModify) plan;
            switch (dml.getOperation()) {
                case INSERT:
                    return planInsert(dml, returnValues);
                case DELETE:
                    return planDelete(dml, returnValues);
                case UPDATE:
                    return planUpdate(dml, returnValues);
                default:
                    throw new StatementExecutionException("unsupport DML operation " + dml.getOperation());
            }
        } else if (plan instanceof EnumerableTableScan) {
            EnumerableTableScan scan = (EnumerableTableScan) plan;
            return planEnumerableTableScan(scan);
        } else if (plan instanceof EnumerableProject) {
            EnumerableProject scan = (EnumerableProject) plan;
            return planProject(scan);
        } else if (plan instanceof EnumerableValues) {
            EnumerableValues scan = (EnumerableValues) plan;
            return planValues(scan);
        } else if (plan instanceof EnumerableSort) {
            EnumerableSort scan = (EnumerableSort) plan;
            return planSort(scan);
        } else if (plan instanceof EnumerableLimit) {
            EnumerableLimit scan = (EnumerableLimit) plan;
            return planLimit(scan);
        } else if (plan instanceof EnumerableInterpreter) {
            EnumerableInterpreter scan = (EnumerableInterpreter) plan;
            return planInterpreter(scan, returnValues);
        } else if (plan instanceof EnumerableFilter) {
            EnumerableFilter scan = (EnumerableFilter) plan;
            return planFilter(scan, returnValues);
        } else if (plan instanceof EnumerableAggregate) {
            EnumerableAggregate scan = (EnumerableAggregate) plan;
            return planAggregate(scan, returnValues);
        }

        throw new StatementExecutionException("not implented " + plan.getRelTypeName());
    }

    private InsertOp planInsert(EnumerableTableModify dml, boolean returnValues) {

        PlannerOp input = convertRelNode(dml.getInput(), false);

        final String tableSpace = dml.getTable().getQualifiedName().get(0);
        final String tableName = dml.getTable().getQualifiedName().get(1);

        try {
            return new InsertOp(tableSpace, tableName, input, returnValues);
        } catch (IllegalArgumentException err) {
            throw new StatementExecutionException(err);
        }

    }

    private DeleteOp planDelete(EnumerableTableModify dml, boolean returnValues) {
        PlannerOp input = convertRelNode(dml.getInput(), false);

        final String tableSpace = dml.getTable().getQualifiedName().get(0);
        final String tableName = dml.getTable().getQualifiedName().get(1);
        final TableImpl tableImpl
                = (TableImpl) dml.getTable().unwrap(org.apache.calcite.schema.Table.class);
        Table table = tableImpl.tableManager.getTable();
        DeleteStatement delete = null;
        if (input instanceof TableScanOp) {
            delete = new DeleteStatement(tableSpace, tableName, null, null);
        } else if (input instanceof FilterOp) {
            FilterOp filter = (FilterOp) input;
            if (filter.getInput() instanceof TableScanOp) {
                SQLRecordPredicate pred = new SQLRecordPredicate(table, null, filter.getCondition());
                delete = new DeleteStatement(tableSpace, tableName, null, pred);
            }
        }
        if (delete == null) {
            throw new StatementExecutionException("unsupported input type for DELETE " + input.getClass());
        }
        return new DeleteOp(delete.setReturnValues(returnValues));

    }

    private UpdateOp planUpdate(EnumerableTableModify dml, boolean returnValues) {
        PlannerOp input = convertRelNode(dml.getInput(), false);
        List<String> updateColumnList = dml.getUpdateColumnList();
        List<RexNode> sourceExpressionList = dml.getSourceExpressionList();
        final String tableSpace = dml.getTable().getQualifiedName().get(0);
        final String tableName = dml.getTable().getQualifiedName().get(1);
        final TableImpl tableImpl
                = (TableImpl) dml.getTable().unwrap(org.apache.calcite.schema.Table.class);
        Table table = tableImpl.tableManager.getTable();
        List<CompiledSQLExpression> expressions = new ArrayList<>(sourceExpressionList.size());
        for (RexNode node : sourceExpressionList) {
            expressions.add(SQLExpressionCompiler.compileExpression(node));
        }
        RecordFunction function = new SQLRecordFunction(updateColumnList, table, expressions);
        UpdateStatement update = null;
        if (input instanceof TableScanOp) {
            update = new UpdateStatement(tableSpace, tableName, null, function, null);
        } else if (input instanceof FilterOp) {
            FilterOp filter = (FilterOp) input;
            if (filter.getInput() instanceof TableScanOp) {
                SQLRecordPredicate pred = new SQLRecordPredicate(table, null, filter.getCondition());
                update = new UpdateStatement(tableSpace, tableName, null, function, pred);
            }
        } else if (input instanceof ProjectOp) {
            ProjectOp proj = (ProjectOp) input;
            if (proj.getInput() instanceof TableScanOp) {
                update = new UpdateStatement(tableSpace, tableName, null, function, null);
            } else if (proj.getInput() instanceof FilterOp) {
                FilterOp filter = (FilterOp) proj.getInput();
                if (filter.getInput() instanceof TableScanOp) {
                    SQLRecordPredicate pred = new SQLRecordPredicate(table, null, filter.getCondition());
                    update = new UpdateStatement(tableSpace, tableName, null, function, pred);
                }
            } else if (proj.getInput() instanceof FilteredTableScanOp) {
                FilteredTableScanOp filter = (FilteredTableScanOp) proj.getInput();
                Predicate pred = filter.getPredicate();
                update = new UpdateStatement(tableSpace, tableName, null, function, pred);
            }
        }
        if (update == null) {
            throw new StatementExecutionException("unsupported input type " + input + " for UPDATE " + input.getClass());
        }
        return new UpdateOp(update.setReturnValues(returnValues));

    }

    private PlannerOp planEnumerableTableScan(EnumerableTableScan scan) {
        final String tableSpace = scan.getTable().getQualifiedName().get(0);
        final TableImpl tableImpl
                = (TableImpl) scan.getTable().unwrap(org.apache.calcite.schema.Table.class);
        Table table = tableImpl.tableManager.getTable();
        return new TableScanOp(new ScanStatement(tableSpace, table, null));
    }

    private PlannerOp planProject(EnumerableProject op) {
        PlannerOp input = convertRelNode(op.getInput(), false);

        List<CompiledSQLExpression> fields = new ArrayList<>(op.getProjects().size());
        Column[] columns = new Column[op.getProjects().size()];
        RelDataType rowType = op.getRowType();
        int i = 0;
        for (RexNode node : op.getProjects()) {
            CompiledSQLExpression exp = SQLExpressionCompiler.compileExpression(node);
            fields.add(exp);
            Column col = Column.column(rowType.getFieldNames().get(i), convertToHerdType(node.getType()));
            columns[i++] = col;
        }
        return new ProjectOp(rowType.getFieldNames(),
                columns,
                fields, input);

    }

    private PlannerOp planValues(EnumerableValues op) {

        List<List<CompiledSQLExpression>> tuples = new ArrayList<>(op.getTuples().size());
        RelDataType rowType = op.getRowType();
        List<RelDataTypeField> fieldList = rowType.getFieldList();

        Column[] columns = new Column[fieldList.size()];
        for (ImmutableList<RexLiteral> tuple : op.getTuples()) {
            List<CompiledSQLExpression> row = new ArrayList<>(tuple.size());
            for (RexLiteral node : tuple) {
                CompiledSQLExpression exp = SQLExpressionCompiler.compileExpression(node);
                row.add(exp);
            }
            tuples.add(row);
        }
        int i = 0;
        String[] fieldNames = new String[fieldList.size()];
        for (RelDataTypeField field : fieldList) {
            Column col = Column.column(field.getName(), convertToHerdType(field.getType()));
            fieldNames[i] = field.getName();
            columns[i++] = col;
        }
        return new ValuesOp(manager.getNodeId(), fieldNames,
                columns, tuples);

    }

    private PlannerOp planSort(EnumerableSort op) {
        PlannerOp input = convertRelNode(op.getInput(), false);
        RelCollation collation = op.getCollation();
        List<RelFieldCollation> fieldCollations = collation.getFieldCollations();
        boolean[] directions = new boolean[fieldCollations.size()];
        int[] fields = new int[fieldCollations.size()];
        int i = 0;
        for (RelFieldCollation col : fieldCollations) {
            RelFieldCollation.Direction direction = col.getDirection();
            int index = col.getFieldIndex();
            directions[i] = direction == RelFieldCollation.Direction.ASCENDING
                    || direction == RelFieldCollation.Direction.STRICTLY_ASCENDING;
            fields[i++] = index;
        }
        return new SortOp(input, directions, fields);

    }

    private PlannerOp planInterpreter(EnumerableInterpreter op, boolean returnValues) {
        // NOOP
        return convertRelNode(op.getInput(), returnValues);
    }

    private PlannerOp planLimit(EnumerableLimit op) {
        PlannerOp input = convertRelNode(op.getInput(), false);
        CompiledSQLExpression maxRows = SQLExpressionCompiler.compileExpression(op.fetch);
        CompiledSQLExpression offset = SQLExpressionCompiler.compileExpression(op.offset);
        return new LimitOp(input, maxRows, offset);

    }

    private PlannerOp planFilter(EnumerableFilter op, boolean returnValues) {
        PlannerOp input = convertRelNode(op.getInput(), returnValues);
        CompiledSQLExpression condition = SQLExpressionCompiler.compileExpression(op.getCondition());
        return new FilterOp(input, condition);

    }

    private PlannerOp planAggregate(EnumerableAggregate op, boolean returnValues) {

        List<RelDataTypeField> fieldList = op.getRowType().getFieldList();

        List<AggregateCall> calls = op.getAggCallList();
        String[] fieldnames = new String[fieldList.size()];
        String[] aggtypes = new String[calls.size()];
        Column[] columns = new Column[fieldList.size()];
        List<Integer> groupedFiledsIndexes = op.getGroupSet().toList();
        List<List<Integer>> argLists = new ArrayList<>(calls.size());
        int i = 0;

        int idaggcall = 0;
        for (RelDataTypeField c : fieldList) {
            int type = convertToHerdType(c.getType());
            Column co = Column.column(c.getName(), type);
            columns[i] = co;
            fieldnames[i] = c.getName();
            i++;
        }
        for (AggregateCall call : calls) {
            aggtypes[idaggcall++] = call.getAggregation().getName();
            argLists.add(call.getArgList());
        }
        PlannerOp input = convertRelNode(op.getInput(), returnValues);
        return new AggregateOp(input, fieldnames, columns, aggtypes, argLists, groupedFiledsIndexes);
    }

    private static int convertToHerdType(RelDataType type) {
        switch (type.getSqlTypeName()) {
            case VARCHAR:
                return ColumnTypes.STRING;
            case BOOLEAN:
                return ColumnTypes.BOOLEAN;
            case INTEGER:
                return ColumnTypes.INTEGER;
            case BIGINT:
                return ColumnTypes.LONG;
            case VARBINARY:
                return ColumnTypes.BYTEARRAY;
            case NULL:
                return ColumnTypes.NULL;
            case ANY:
                return ColumnTypes.ANYTYPE;
            default:
                throw new StatementExecutionException("unsuppoted expression type " + type.getSqlTypeName());
        }
    }

    private static class TableImpl extends AbstractTable
            implements ModifiableTable, ScannableTable {

        AbstractTableManager tableManager;

        private TableImpl(AbstractTableManager tableManager) {
            this.tableManager = tableManager;
        }

        @Override
        public RelDataType getRowType(RelDataTypeFactory typeFactory) {
            RelDataTypeFactory.Builder builder = new RelDataTypeFactory.Builder(typeFactory);
            for (Column c : tableManager.getTable().getColumns()) {
                builder.add(c.name, convertType(c.type, typeFactory));
            }
            return builder.build();
        }

        @Override
        public Statistic getStatistic() {
            // TODO
            return Statistics.of(tableManager.getStats().getTablesize(),
                    ImmutableList.<ImmutableBitSet>of());
        }

        @Override
        public Collection getModifiableCollection() {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public TableModify toModificationRel(RelOptCluster cluster, RelOptTable table, Prepare.CatalogReader catalogReader, RelNode child, TableModify.Operation operation, List<String> updateColumnList, List<RexNode> sourceExpressionList, boolean flattened) {
            return LogicalTableModify.create(table, catalogReader, child, operation,
                    updateColumnList, sourceExpressionList, flattened);
        }

        @Override
        public <T> Queryable<T> asQueryable(QueryProvider queryProvider, SchemaPlus schema, String tableName) {
            throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public Type getElementType() {
            return Object.class;
        }

        @Override
        public Expression getExpression(SchemaPlus schema, String tableName, Class clazz) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public Enumerable<Object[]> scan(DataContext root) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        private static RelDataType convertType(int type, RelDataTypeFactory typeFactory) {
            switch (type) {
                case ColumnTypes.BOOLEAN:
                    return typeFactory.createSqlType(SqlTypeName.BOOLEAN);
                case ColumnTypes.INTEGER:
                    return typeFactory.createSqlType(SqlTypeName.INTEGER);
                case ColumnTypes.STRING:
                    return typeFactory.createSqlType(SqlTypeName.VARCHAR);
                case ColumnTypes.BYTEARRAY:
                    return typeFactory.createSqlType(SqlTypeName.VARBINARY);
                case ColumnTypes.LONG:
                    return typeFactory.createSqlType(SqlTypeName.BIGINT);
                case ColumnTypes.TIMESTAMP:
                    return typeFactory.createSqlType(SqlTypeName.TIMESTAMP);
                default:
                    return typeFactory.createSqlType(SqlTypeName.ANY);

            }
        }

    }

}