package net.openhft.chronicle.wire;

import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.pool.ClassAliasPool;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Created by peter.lawrey on 10/02/2016.
 */
public class WireTypeTest {
    static {
        ClassAliasPool.CLASS_ALIASES.addAlias(TestMarshallable.class);
    }

    @Test
    public void testAsString() throws Exception {
        TestMarshallable tm = new TestMarshallable();
        tm.setCount(1);
        tm.setName("name");
        assertEquals("!TestMarshallable {\n" +
                "  name: name,\n" +
                "  count: 1\n" +
                "}\n", WireType.TEXT.asString(tm));
        assertEquals("00000000 B6 10 54 65 73 74 4D 61  72 73 68 61 6C 6C 61 62 ··TestMa rshallab\n" +
                "00000010 6C 65 82 11 00 00 00 C4  6E 61 6D 65 E4 6E 61 6D le······ name·nam\n" +
                "00000020 65 C5 63 6F 75 6E 74 01                          e·count·         \n", WireType.BINARY.asString(tm));
        assertEquals("00000000 10 54 65 73 74 4D 61 72  73 68 61 6C 6C 61 62 6C ·TestMar shallabl\n" +
                "00000010 65 09 00 00 00 04 6E 61  6D 65 01 00 00 00       e·····na me····  \n", WireType.RAW.asString(tm));
    }

    @Test
    public void testFromString() throws Exception {
        String asText = "!TestMarshallable {\n" +
                "  name: name,\n" +
                "  count: 1\n" +
                "}\n";
        TestMarshallable tm = new TestMarshallable();
        tm.setCount(1);
        tm.setName("name");
        assertEquals(tm, WireType.TEXT.fromString(asText));

        String asBinary = "00000000 B6 10 54 65 73 74 4D 61  72 73 68 61 6C 6C 61 62 ··TestMa rshallab\n" +
                "00000010 6C 65 82 11 00 00 00 C4  6E 61 6D 65 E4 6E 61 6D le······ name·nam\n" +
                "00000020 65 C5 63 6F 75 6E 74 01                          e·count·         \n";
        assertEquals(tm, WireType.BINARY.fromString(asBinary));

/* NOT Supported
        String asRaw = "00000000 10 54 65 73 74 4D 61 72  73 68 61 6C 6C 61 62 6C ·TestMar shallabl\n" +
                "00000010 65 09 00 00 00 04 6E 61  6D 65 01 00 00 00       e·····na me····  \n";
        assertEquals(tm, WireType.RAW.fromString(asRaw));
*/
    }

    @Test
    public void testFromFile() throws Exception {
        TestMarshallable tm = new TestMarshallable();
        tm.setCount(1);
        tm.setName("name");

        for (WireType wt : WireType.values()) {
            if (wt == WireType.RAW || wt == WireType.READ_ANY || wt == WireType.CSV)
                continue;
            String tmp = OS.getTarget() + "/testFromFile-" + System.nanoTime();
            wt.toFile(tmp, tm);
            Object o = wt.fromFile(tmp);
            assertEquals(tm, o);
        }
    }
}