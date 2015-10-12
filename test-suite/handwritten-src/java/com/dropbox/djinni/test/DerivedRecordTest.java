package com.dropbox.djinni.test;

import junit.framework.TestCase;

public class DerivedRecordTest extends TestCase {

    private final ChildRecord record1 = new ChildRecord(1, "String1", 2, 3);

    public void testRoundTrip() {
        GrandParentRecord theRecord = TestHelpers.getDerivedRecord(record1);
        assertTrue(theRecord instanceof ChildRecord);
        assertEquals(theRecord, record1);
    }

}
