package xyz.melodylsp.codec.system;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.util.Map;

import org.junit.Test;

public final class GameLeaControllerTest {
    @Test
    public void quickBackgroundReturnCancelsOldExit() {
        GameLeaController.ExitGate gate = new GameLeaController.ExitGate();
        gate.onGameMode(true);
        long firstExit = gate.onGameMode(false);
        assertTrue(gate.canExit(firstExit));
        gate.onGameMode(true);
        assertFalse(gate.canExit(firstExit));
        long secondExit = gate.onGameMode(false);
        assertFalse(gate.canExit(firstExit));
        assertTrue(gate.canExit(secondExit));
    }

    @Test
    public void manualChoiceInvalidatesQueuedExit() {
        GameLeaController.ExitGate gate = new GameLeaController.ExitGate();
        long exit = gate.onGameMode(false);
        gate.invalidate();
        assertFalse(gate.canExit(exit));
    }

    @Test
    public void latestOfficialSwitchWinsOverPendingCacheUpdate() {
        assertEquals("off", GameLeaController.resolveOfficialGamePreference(
                "game.a", "game.a:0", Map.of("game.a", true)));
        assertEquals("on", GameLeaController.resolveOfficialGamePreference(
                "game.a", "game.a:1", Map.of("game.a", false)));
    }

    @Test
    public void otherGameDoesNotEraseRememberedOfficialDecision() {
        assertEquals("off", GameLeaController.resolveOfficialGamePreference(
                "game.a", "game.b:1", Map.of("game.a", false)));
        assertEquals("unknown", GameLeaController.resolveOfficialGamePreference(
                "game.a", "game.b:0", Map.of()));
    }

    @Test
    public void firstConfirmedTimerCanCorrectAnEarlyProvisionalExit() {
        GameLeaController.ExitDeadline timer = new GameLeaController.ExitDeadline();
        assertTrue(timer.update(1_000L, 2_000L, false));
        assertTrue(timer.update(1_250L, 29_750L, true));
        assertFalse(timer.expired(3_000L));
        assertTrue(timer.expired(31_000L));
    }

    @Test
    public void repeatQueriesDoNotExtendGraceButProcessDeathShortensIt() {
        GameLeaController.ExitDeadline timer = new GameLeaController.ExitDeadline();
        timer.update(1_000L, 30_000L, true);
        assertFalse(timer.update(10_000L, 30_000L, true));
        assertTrue(timer.update(10_000L, 10_000L, true));
        assertFalse(timer.expired(19_999L));
        assertTrue(timer.expired(20_000L));
        timer.reset();
        assertFalse(timer.expired(60_000L));
    }

    @Test
    public void backgroundUsesThirtySecondsEvenWhenVendorTimerWouldReportZero() {
        assertEquals(29_750L, GameLeaController.exitGraceRemaining(1_250L, 1_000L, 0L, false));
        assertEquals(25_000L, GameLeaController.exitGraceRemaining(6_000L, 1_000L, 30_000L, false));
        assertEquals(0L, GameLeaController.exitGraceRemaining(31_000L, 1_000L, 30_000L, false));
    }

    @Test
    public void confirmedMainDeathHasItsOwnTenSecondClock() {
        assertEquals(9_000L, GameLeaController.exitGraceRemaining(12_000L, 11_000L, 0L, true));
        assertEquals(0L, GameLeaController.exitGraceRemaining(21_000L, 11_000L, 10_000L, true));
    }
}
