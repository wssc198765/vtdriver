/*
Copyright 2021 JD Project Authors. Licensed under Apache-2.0.

Copyright 2019 The Vitess Authors.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.jd.jdbc.engine.table;

import com.google.common.collect.Lists;
import com.jd.jdbc.IExecute;
import com.jd.jdbc.context.IContext;
import com.jd.jdbc.engine.Engine;
import com.jd.jdbc.engine.OrderByParams;
import com.jd.jdbc.engine.PrimitiveEngine;
import com.jd.jdbc.engine.Vcursor;
import com.jd.jdbc.evalengine.EvalEngine;
import com.jd.jdbc.sqlparser.ast.statement.SQLSelectQuery;
import com.jd.jdbc.sqltypes.VtPlanValue;
import com.jd.jdbc.sqltypes.VtResultSet;
import com.jd.jdbc.sqltypes.VtResultValue;
import com.jd.jdbc.sqltypes.VtValue;
import com.jd.jdbc.tindexes.ActualTable;
import com.jd.jdbc.tindexes.LogicTable;
import com.jd.jdbc.tindexes.TableIndex;
import com.jd.jdbc.vindexes.VKeyspace;
import io.vitess.proto.Query;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.Getter;

@Data
public class TableRouteEngine implements PrimitiveEngine {
    /**
     * RouteOpcode is a number representing the opcode
     * for the Route primitve
     */
    private Engine.RouteOpcode routeOpcode;

    /**
     * Keyspace specifies the keyspace to send the query to.
     */
    private VKeyspace keyspace;

    /**
     * Query specifies the query to be executed.
     */
    private SQLSelectQuery selectQuery;

    /**
     * TableName specifies the table to send the query to.
     */
    private String tableName = "";

    /**
     * Values specifies the vindex values to use for routing table.
     */
    private TableIndex tableIndex;

    /**
     * Values specifies the vindex values to use for routing.
     */
    private List<VtPlanValue> vtPlanValueList = new ArrayList<>();

    /**
     * OrderBy specifies the key order for merge sorting. This will be
     * set only for scatter queries that need the results to be
     * merge-sorted.
     */
    private List<OrderByParams> orderBy = new ArrayList<>();

    /**
     * TruncateColumnCount specifies the number of columns to return
     * in the final result. Rest of the columns are truncated
     * from the result received. If 0, no truncation happens.
     */
    private Integer truncateColumnCount = 0;

    private List<LogicTable> logicTables = new ArrayList<>();

    private PrimitiveEngine executeEngine;

    public TableRouteEngine(final Engine.RouteOpcode routeOpcode, final VKeyspace keyspace) {
        this.routeOpcode = routeOpcode;
        this.keyspace = keyspace;
    }

    @Override
    public String getKeyspaceName() {
        return this.keyspace.getName();
    }

    @Override
    public String getTableName() {
        return this.tableName;
    }

    @Override
    public IExecute.ExecuteMultiShardResponse execute(final IContext ctx, final Vcursor vcursor, final Map<String, Query.BindVariable> bindVariableMap, final boolean wantFields) throws SQLException {
        Engine.TableDestinationResponse tableDestinationResponse = this.getResolveDestinationResult(bindVariableMap);
        VtResultSet resultSet = new VtResultSet();
        // No route
        if (tableDestinationResponse == null) {
            if (wantFields) {
                resultSet = this.getFields(vcursor, new HashMap<>(16, 1));
                return new IExecute.ExecuteMultiShardResponse(resultSet);
            }
            return new IExecute.ExecuteMultiShardResponse(resultSet);
        }
        if (executeEngine.canResolveShardQuery()) {

            VtResultSet tableBatchExecuteResult = Engine.getTableBatchExecuteResult(ctx, executeEngine, vcursor, bindVariableMap, tableDestinationResponse);
            if (this.orderBy == null || this.orderBy.isEmpty()) {
                return new IExecute.ExecuteMultiShardResponse(tableBatchExecuteResult);
            }
            resultSet = this.sort(tableBatchExecuteResult);
            return new IExecute.ExecuteMultiShardResponse(resultSet);
        } else {
            throw new SQLException("unsupported engine for partitioned table");
        }
    }

    @Override
    public Boolean needsTransaction() {
        return false;
    }

    private Engine.TableDestinationResponse getResolveDestinationResult(final Map<String, Query.BindVariable> bindVariableMap) throws SQLException {
        Engine.TableDestinationResponse tableDestinationResponse;
        switch (this.routeOpcode) {
            case SelectScatter:
                tableDestinationResponse = this.paramsAllShard(bindVariableMap);
                break;
            case SelectEqual:
            case SelectEqualUnique:
                tableDestinationResponse = this.paramsSelectEqual(bindVariableMap);
                break;
            case SelectIN:
                tableDestinationResponse = this.paramsSelectIn(bindVariableMap);
                break;
            default:
                // Unreachable.
                throw new SQLException("unsupported query route: " + routeOpcode);
        }
        return tableDestinationResponse;
    }

    private Engine.TableDestinationResponse paramsAllShard(final Map<String, Query.BindVariable> bindVariableMap) throws SQLException {
        List<List<ActualTable>> allActualTableGroup = this.getAllActualTableGroup(this.logicTables);
        List<Map<String, Query.BindVariable>> bindVariableList = new ArrayList<>();
        for (int i = 0; i < allActualTableGroup.size(); i++) {
            bindVariableList.add(bindVariableMap);
        }
        return new Engine.TableDestinationResponse(allActualTableGroup, bindVariableList);
    }

    private List<List<ActualTable>> getAllActualTableGroup(final List<LogicTable> logicTables) {
        List<List<ActualTable>> allActualTableGroup = new ArrayList<>();
        if (logicTables == null || logicTables.isEmpty()) {
            return allActualTableGroup;
        }
        for (ActualTable act : logicTables.get(0).getActualTableList()) {
            List<ActualTable> tableGroup = Lists.newArrayList(act);
            allActualTableGroup.add(tableGroup);
        }
        for (int i = 1; i < logicTables.size(); i++) {
            List<ActualTable> actualTables = logicTables.get(i).getActualTableList();
            List<List<ActualTable>> tempActualTableGroup = new ArrayList<>();
            for (int j = 0; j < allActualTableGroup.size(); j++) {
                for (int k = 0; k < actualTables.size(); k++) {
                    List<ActualTable> actualTableGroup = new ArrayList<>();
                    for (ActualTable act : allActualTableGroup.get(j)) {
                        actualTableGroup.add(act);
                    }
                    actualTableGroup.add(actualTables.get(k));
                    tempActualTableGroup.add(actualTableGroup);
                }
            }
            allActualTableGroup = tempActualTableGroup;
        }
        return allActualTableGroup;
    }

    private Engine.TableDestinationResponse paramsSelectEqual(final Map<String, Query.BindVariable> bindVariableMap) throws SQLException {
        VtValue value = this.vtPlanValueList.get(0).resolveValue(bindVariableMap);
        List<ActualTable> actualTables = new ArrayList<>();
        for (LogicTable ltb : this.logicTables) {
            ActualTable actualTable = ltb.map(value);
            if (actualTable == null) {
                throw new SQLException("cannot calculate split table, logic table: " + ltb.getLogicTable());
            }
            actualTables.add(actualTable);
        }
        return new Engine.TableDestinationResponse(
            new ArrayList<List<ActualTable>>() {{
                add(actualTables);
            }},
            new ArrayList<Map<String, Query.BindVariable>>() {{
                add(bindVariableMap);
            }});
    }

    private Engine.TableDestinationResponse paramsSelectIn(final Map<String, Query.BindVariable> bindVariableMap) throws SQLException {
        List<VtValue> keys = this.vtPlanValueList.get(0).resolveList(bindVariableMap);
        List<List<ActualTable>> tables = new ArrayList<>();
        List<List<Query.Value>> planValuePerTableGroup = new ArrayList<>();
        Map<String, Integer> actualTableMap = new HashMap<>();
        for (VtValue key : keys) {
            List<ActualTable> actualTables = new ArrayList<>();
            StringBuilder tableGroup = new StringBuilder();
            for (LogicTable ltb : this.logicTables) {
                ActualTable actualTable = ltb.map(key);
                if (actualTable == null) {
                    throw new SQLException("cannot calculate split table, logic table: " + ltb.getLogicTable());
                }
                actualTables.add(actualTable);
                tableGroup.append(actualTable.getActualTableName());
            }
            if (actualTableMap.containsKey(tableGroup.toString())) {
                planValuePerTableGroup.get(actualTableMap.get(tableGroup.toString())).add(key.toQueryValue());
            } else {
                planValuePerTableGroup.add(new ArrayList<>());
                actualTableMap.put(tableGroup.toString(), planValuePerTableGroup.size() - 1);
                planValuePerTableGroup.get(actualTableMap.get(tableGroup.toString())).add(key.toQueryValue());
                tables.add(actualTables);
            }
        }
        return new Engine.TableDestinationResponse(tables, Engine.shardVars(bindVariableMap, planValuePerTableGroup));
    }

    /**
     * @param in
     * @return
     */
    private VtResultSet sort(final VtResultSet in) throws SQLException {
        // Since Result is immutable, we make a copy.
        // The copy can be shallow because we won't be changing
        // the contents of any row.
        VtResultSet out = new VtResultSet();
        out.setFields(in.getFields());
        out.setRows(in.getRows());
        out.setRowsAffected(in.getRowsAffected());
        out.setInsertID(in.getInsertID());

        VtResultComparator comparator = new VtResultComparator(this.orderBy);
        out.getRows().sort(comparator);
        if (comparator.exception != null) {
            throw comparator.exception;
        }
        return out;
    }

    static class VtResultComparator implements Comparator<List<VtResultValue>> {
        private final List<OrderByParams> orderBy;

        @Getter
        private SQLException exception;

        public VtResultComparator(List<OrderByParams> orderBy) {
            this.orderBy = orderBy;
            this.exception = null;
        }

        @Override
        public int compare(List<VtResultValue> o1, List<VtResultValue> o2) {
            // If there are any errors below, the function sets
            // the external err and returns true. Once err is set,
            // all subsequent calls return true. This will make
            // Slice think that all elements are in the correct
            // order and return more quickly.
            for (OrderByParams order : this.orderBy) {
                if (exception != null) {
                    return -1;
                }
                Integer cmp;
                try {
                    cmp = EvalEngine.nullSafeCompare(o1.get(order.getCol()), o2.get(order.getCol()));
                } catch (SQLException e) {
                    this.exception = e;
                    return -1;
                }
                if (cmp == 0) {
                    continue;
                }
                if (order.getDesc()) {
                    cmp = -cmp;
                }
                return cmp;
            }
            return 0;
        }
    }

}
