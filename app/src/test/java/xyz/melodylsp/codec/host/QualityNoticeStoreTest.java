package xyz.melodylsp.codec.host;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class QualityNoticeStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final String FIRST = "AA:BB:CC:DD:EE:01";
    private static final String SECOND = "AA:BB:CC:DD:EE:02";

    @Test
    public void shownReceiptSurvivesNewStoreInstance() throws Exception {
        File directory = temporary.newFolder();
        QualityNoticeStore first = new QualityNoticeStore(directory);
        try (QualityNoticeStore.Reservation receipt = first.reserve(FIRST)) {
            assertNotNull(receipt);
            receipt.markShown();
        }
        assertNull(first.reserve(FIRST));
        assertNull(new QualityNoticeStore(directory).reserve(FIRST));
    }

    @Test
    public void failedOrDismissedBeforeDrawCanBeRetried() throws Exception {
        File directory = temporary.newFolder();
        try (QualityNoticeStore.Reservation receipt = new QualityNoticeStore(directory).reserve(FIRST)) {
            assertNotNull(receipt);
        }
        try (QualityNoticeStore.Reservation retry = new QualityNoticeStore(directory).reserve(FIRST)) {
            assertNotNull(retry);
        }
    }

    @Test
    public void overlappingOwnersCannotShowTheSameNotice() throws Exception {
        File directory = temporary.newFolder();
        QualityNoticeStore first = new QualityNoticeStore(directory);
        QualityNoticeStore second = new QualityNoticeStore(directory);
        try (QualityNoticeStore.Reservation receipt = first.reserve(FIRST)) {
            assertNotNull(receipt);
            assertNull(second.reserve(FIRST));
            receipt.markShown();
        }
        assertNull(second.reserve(FIRST));
    }

    @Test
    public void otherHeadsetsHaveIndependentReceipts() throws Exception {
        QualityNoticeStore store = new QualityNoticeStore(temporary.newFolder());
        try (QualityNoticeStore.Reservation receipt = store.reserve(FIRST)) {
            receipt.markShown();
        }
        try (QualityNoticeStore.Reservation other = store.reserve(SECOND)) {
            assertNotNull(other);
        }
    }

    @Test
    public void normalizedMacUsesTheSameReceipt() throws Exception {
        QualityNoticeStore store = new QualityNoticeStore(temporary.newFolder());
        try (QualityNoticeStore.Reservation receipt = store.reserve(" aa:bb:cc:dd:ee:01 ")) {
            receipt.markShown();
        }
        assertNull(store.reserve(FIRST));
    }

    @Test
    public void invalidMacCannotCreateAReceiptOrEscapeTheDirectory() throws Exception {
        File directory = new File(temporary.getRoot(), "notices");
        QualityNoticeStore store = new QualityNoticeStore(directory);
        assertNull(store.reserve(null));
        assertNull(store.reserve(""));
        assertNull(store.reserve("../escape"));
        assertFalse(directory.exists());
    }
}
