package ai.hermes.mobile.runtime.bridge.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HermesTaskStateMachineTest {
    @Test
    fun oneTaskMovesFromRunningToCompleted() {
        val running = HermesTaskStateMachine.begin(HermesTaskSession(), 7, "  inspect phone  ")
        val completed = HermesTaskStateMachine.complete(running, 7, "  done  ")

        assertEquals(HermesTaskPhase.RUNNING, running.phase)
        assertEquals("inspect phone", running.prompt)
        assertEquals(HermesTaskPhase.COMPLETED, completed.phase)
        assertEquals("done", completed.response)
    }

    @Test
    fun staleCompletionCannotOverwriteCancelledTask() {
        val running = HermesTaskStateMachine.begin(HermesTaskSession(), 8, "inspect phone")
        val stopping = HermesTaskStateMachine.requestCancel(running)
        val cancelled = HermesTaskStateMachine.finishCancel(stopping)

        assertEquals(HermesTaskPhase.STOPPING, stopping.phase)
        assertSame(stopping, HermesTaskStateMachine.complete(stopping, 8, "late result"))
        assertEquals(HermesTaskPhase.CANCELLED, cancelled.phase)
    }

    @Test
    fun staleTaskIdCannotOverwriteNewerTask() {
        val running = HermesTaskStateMachine.begin(HermesTaskSession(), 9, "new task")

        assertSame(running, HermesTaskStateMachine.fail(running, 8, HermesTaskFailure.EXECUTION))
    }
}
