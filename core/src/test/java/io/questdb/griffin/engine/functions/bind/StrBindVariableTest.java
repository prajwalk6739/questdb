/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2019 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package io.questdb.griffin.engine.functions.bind;

import io.questdb.std.str.StringSink;
import io.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Test;

public class StrBindVariableTest {
    @Test
    public void testNull() {
        StrBindVariable variable = new StrBindVariable(null);
        Assert.assertNull(variable.getStr(null));
        Assert.assertNull(variable.getStrB(null));
        Assert.assertEquals(-1, variable.getStrLen(null));

        StringSink sink = new StringSink();
        variable.getStr(null, sink);
        Assert.assertEquals(0, sink.length());
    }

    @Test
    public void testSimple() {
        String expected = "xyz";
        StrBindVariable variable = new StrBindVariable(expected);
        TestUtils.assertEquals(expected, variable.getStr(null));
        TestUtils.assertEquals(expected, variable.getStrB(null));
        Assert.assertEquals(expected.length(), variable.getStrLen(null));

        StringSink sink = new StringSink();
        variable.getStr(null, sink);
        TestUtils.assertEquals(expected, sink);
    }
}