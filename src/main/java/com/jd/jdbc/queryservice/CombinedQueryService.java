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

package com.jd.jdbc.queryservice;

import com.jd.jdbc.context.IContext;
import com.jd.jdbc.discovery.TabletHealthCheck;
import com.jd.jdbc.sqltypes.BatchVtResultSet;
import com.jd.jdbc.sqltypes.BeginBatchVtResultSet;
import com.jd.jdbc.sqltypes.BeginVtResultSet;
import com.jd.jdbc.sqltypes.VtResultSet;
import com.jd.jdbc.vitess.VitessJdbcProperyUtil;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import io.vitess.proto.Query;
import io.vitess.proto.Topodata;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * QueryService that combines the NativeProtocol and GRPC
 **/
public class CombinedQueryService implements IQueryService, IHealthCheckQueryService {
    private final Topodata.Tablet tablet;

    private final String user;

    private final String password;

    private final Properties dsProperties;

    private final Properties properties;

    private final IQueryService tabletQueryService;

    private volatile IQueryService nativeQueryService;

    public CombinedQueryService(ManagedChannel channel, Topodata.Tablet tablet, String user, String password, Properties dsProperties, Properties properties) {
        this.tablet = tablet;
        this.user = user;
        this.password = password;
        this.dsProperties = dsProperties;
        this.properties = properties;
        tabletQueryService = new TabletQueryService(channel);

        if (Topodata.TabletType.MASTER.equals(VitessJdbcProperyUtil.getTabletType(dsProperties)) && Topodata.TabletType.MASTER.equals(tablet.getType())) {
            nativeQueryService = new NativeQueryService(tablet, user, password, dsProperties, properties);
        }
    }

    private IQueryService getNativeQueryService() {
        if (nativeQueryService != null) {
            return nativeQueryService;
        }
        synchronized (this) {
            if (nativeQueryService == null) {
                nativeQueryService = new NativeQueryService(tablet, user, password, dsProperties, properties);
            }
            return nativeQueryService;
        }
    }

    @Override
    public void closeNativeQueryService() {
        if (nativeQueryService == null) {
            return;
        }
        synchronized (this) {
            if (nativeQueryService == null) {
                return;
            }
            nativeQueryService.close();
            nativeQueryService = null;
        }
    }

    @Override
    public Query.BeginResponse begin(IContext context, Query.Target target, Query.ExecuteOptions options) {
        return null;
    }

    @Override
    public Query.CommitResponse commit(IContext context, Query.Target target, Long transactionId) throws SQLException {
        return getNativeQueryService().commit(context, target, transactionId);
    }

    @Override
    public Query.RollbackResponse rollback(IContext context, Query.Target target, Long transactionId) throws SQLException {
        return getNativeQueryService().rollback(context, target, transactionId);
    }

    @Override
    public VtResultSet execute(IContext context, Query.Target target, String sql, Map<String, Query.BindVariable> bindVariables, Long transactionId, Long reservedId, Query.ExecuteOptions options)
        throws SQLException {
        return getNativeQueryService().execute(context, target, sql, bindVariables, transactionId, reservedId, options);
    }

    @Override
    public StreamIterator streamExecute(IContext context, Query.Target target, String sql, Map<String, Query.BindVariable> bindVariables, Long transactionId, Query.ExecuteOptions options)
        throws SQLException {
        return getNativeQueryService().streamExecute(context, target, sql, bindVariables, transactionId, options);
    }

    @Override
    public BatchVtResultSet executeBatch(IContext context, Query.Target target, List<Query.BoundQuery> queries, Boolean asTransaction, Long transactionId, Query.ExecuteOptions options)
        throws SQLException {
        return getNativeQueryService().executeBatch(context, target, queries, asTransaction, transactionId, options);
    }

    @Override
    public BeginBatchVtResultSet beginExecuteBatch(IContext context, Query.Target target, List<Query.BoundQuery> queries, Boolean asTransaction, Query.ExecuteOptions options) throws SQLException {
        return getNativeQueryService().beginExecuteBatch(context, target, queries, asTransaction, options);
    }

    @Override
    public Query.ReserveBeginExecuteResponse reserveBeginExecute(IContext context, Query.Target target, List<String> preQuries, String sql, Map<String, Query.BindVariable> bindVariables,
                                                                 Query.ExecuteOptions options) throws Exception {
        return null;
    }

    @Override
    public Query.ReserveExecuteResponse reserveExecute(IContext context, Query.Target target, List<String> preQueries, String sql, Map<String, Query.BindVariable> bindVariables, Long transactionID,
                                                       Query.ExecuteOptions options) throws Exception {
        return null;
    }

    @Override
    public Query.ReleaseResponse release(IContext context, Query.Target target, Long transactionID, Long reservedID) throws SQLException {
        getNativeQueryService().release(context, target, transactionID, reservedID);
        tabletQueryService.release(context, target, transactionID, reservedID);
        return Query.ReleaseResponse.newBuilder().build();
    }

    @Override
    public BeginVtResultSet beginExecute(IContext context, Query.Target target, List<String> preQueries, String sql, Map<String, Query.BindVariable> bindVariables, Long reservedId,
                                         Query.ExecuteOptions options) throws SQLException {
        return getNativeQueryService().beginExecute(context, target, preQueries, sql, bindVariables, reservedId, options);
    }

    @Override
    public void streamHealth(TabletHealthCheck thc, StreamObserver<Query.StreamHealthResponse> responseObserver) {
        ((TabletQueryService) tabletQueryService).streamHealth(thc, responseObserver);
    }

    @Override
    public void close() {
        closeNativeQueryService();
        tabletQueryService.close();
    }
}
