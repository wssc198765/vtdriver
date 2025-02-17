/*
Copyright 2021 JD Project Authors.

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

package com.jd.jdbc.table.engine.unshard;

import com.jd.jdbc.table.engine.SelectTest;
import java.sql.SQLException;
import testsuite.internal.TestSuiteShardSpec;

public class SelectUnShardTest extends SelectTest {

    @Override
    protected void getConn() throws SQLException {
        conn = getConnection(Driver.of(TestSuiteShardSpec.NO_SHARDS));
    }

    @Override
    protected void select() throws SQLException, NoSuchFieldException, IllegalAccessException {
        select(false);
    }
}
