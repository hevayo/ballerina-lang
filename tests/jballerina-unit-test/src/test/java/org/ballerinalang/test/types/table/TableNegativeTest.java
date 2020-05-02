/*
 *  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.ballerinalang.test.types.table;

import org.ballerinalang.test.util.BCompileUtil;
import org.ballerinalang.test.util.CompileResult;
import org.testng.Assert;
import org.testng.annotations.Test;

import static org.ballerinalang.test.util.BAssertUtil.validateError;

/**
 * Negative test cases for table.
 *
 * @since 1.3.0
 */
public class TableNegativeTest {

    @Test
    public void testTableNegativeCases() {
        CompileResult compileResult = BCompileUtil.compile("test-src/types/table/table-negative.bal");
        Assert.assertEquals(compileResult.getErrorCount(), 14);
        int index = 0;

        validateError(compileResult, index++, "unknown type 'CusTable'",
                15, 1);
        validateError(compileResult, index++, "table key specifier mismatch. expected: '[id]' " +
                "but found '[id, firstName]'", 20, 28);
        validateError(compileResult, index++, "table key specifier mismatch with key constraint. " +
                "expected: '1' fields but found '0'", 25, 20);
        validateError(compileResult, index++, "table key specifier '[age]' does not match with " +
                "key constraint type '[string]'", 30, 26);
        validateError(compileResult, index++, "table key specifier mismatch. expected: '[id]' but " +
                "found '[address]'", 35, 44);
        validateError(compileResult, index++, "member access is not supported for keyless table " +
                "'customerTable'", 45, 21);
        validateError(compileResult, index++, "invalid constraint type. expected subtype of " +
                "'map<any|error>' but 'int'", 47, 7);
        validateError(compileResult, index++, "multi key member access is not supported for " +
                "type 'map'. only support for subtype of 'table'", 52, 13);
        validateError(compileResult, index++, "field 'name' used in key specifier must be a readonly " +
                "field", 62, 34);
        validateError(compileResult, index++, "field 'name' used in key specifier must be a required " +
                "field", 75, 28);
        validateError(compileResult, index++, "field 'id' used in key specifier must have a literal " +
                "value", 82, 41);
        validateError(compileResult, index++, "member access is not supported for keyless table " +
                "'keylessCusTab'", 87, 27);
        validateError(compileResult, index++, "field 'id' used in key specifier must have a " +
                "literal value", 90, 33);
        validateError(compileResult, index++, "incompatible types: expected 'table<Customer> " +
                "key<string>', found 'table<Customer> key(id)'", 95, 56);
    }
}