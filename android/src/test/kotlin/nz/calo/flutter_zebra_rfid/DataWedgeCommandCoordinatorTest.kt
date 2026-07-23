package nz.calo.flutter_zebra_rfid

import android.content.Intent
import android.os.Bundle
import nz.calo.flutter_zebra_rfid.barcode.DataWedgeCommand
import nz.calo.flutter_zebra_rfid.barcode.DataWedgeCommandCoordinator
import nz.calo.flutter_zebra_rfid.barcode.SEND_RESULT_COMPLETE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
internal class DataWedgeCommandCoordinatorTest {
  @Test
  fun enqueue_sendsOnlyOneCommandUntilItsCorrelatedResultArrives() {
    val sent = mutableListOf<Intent>()
    val completed = mutableListOf<String>()
    val coordinator = coordinator(sent)

    coordinator.enqueue(
      listOf(
        DataWedgeCommand("first", "test.FIRST", "one"),
        DataWedgeCommand("second", "test.SECOND", "two"),
      ),
      onSuccess = { completed += "success" },
      onError = { completed += "error:$it" },
    )

    assertEquals(1, sent.size)
    assertEquals("one", sent.single().getStringExtra("test.FIRST"))
    assertTrue(coordinator.handleResult(successFor(sent.single())))
    assertEquals(2, sent.size)
    assertEquals("two", sent.last().getStringExtra("test.SECOND"))
    assertTrue(coordinator.handleResult(successFor(sent.last())))
    assertEquals(listOf("success"), completed)
  }

  @Test
  fun enqueue_serializesSeparateSequences() {
    val sent = mutableListOf<Intent>()
    val completed = mutableListOf<String>()
    val coordinator = coordinator(sent)

    coordinator.enqueue(
      listOf(DataWedgeCommand("first", "test.FIRST", "one")),
      onSuccess = { completed += "first" },
      onError = { completed += "first-error" },
    )
    coordinator.enqueue(
      listOf(DataWedgeCommand("second", "test.SECOND", "two")),
      onSuccess = { completed += "second" },
      onError = { completed += "second-error" },
    )

    assertEquals(1, sent.size)
    coordinator.handleResult(successFor(sent.single()))
    assertEquals(listOf("first"), completed)
    assertEquals(2, sent.size)
    coordinator.handleResult(successFor(sent.last()))
    assertEquals(listOf("first", "second"), completed)
  }

  @Test
  fun handleResult_acceptsUncorrelatedQueryResponseForExpectedExtra() {
    val sent = mutableListOf<Intent>()
    var status: String? = null
    val coordinator = coordinator(sent)

    coordinator.enqueue(
      listOf(
        DataWedgeCommand(
          label = "scanner-status",
          extraKey = "test.GET_STATUS",
          value = "",
          responseExtra = "test.RESULT_STATUS",
        ),
      ),
      onSuccess = { status = it.single().response as String },
      onError = { throw AssertionError(it) },
    )

    val handled = coordinator.handleResult(
      Intent("com.symbol.datawedge.api.RESULT_ACTION")
        .putExtra("test.RESULT_STATUS", "WAITING"),
    )

    assertTrue(handled)
    assertEquals("WAITING", status)
  }

  @Test
  fun handleResult_ignoresAnotherCorrelatedCommand() {
    val sent = mutableListOf<Intent>()
    var completed = false
    val coordinator = coordinator(sent)
    coordinator.enqueue(
      listOf(DataWedgeCommand("first", "test.FIRST", "one")),
      onSuccess = { completed = true },
      onError = { throw AssertionError(it) },
    )

    val unrelated = Intent("com.symbol.datawedge.api.RESULT_ACTION")
      .putExtra("COMMAND_IDENTIFIER", "someone-else")
      .putExtra("RESULT", "SUCCESS")

    assertFalse(coordinator.handleResult(unrelated))
    assertFalse(completed)
  }

  @Test
  fun handleResult_acceptsCompleteResultWhenEveryProfileModuleSucceeds() {
    val sent = mutableListOf<Intent>()
    var completed = false
    val coordinator = coordinator(sent)
    coordinator.enqueue(
      listOf(
        DataWedgeCommand(
          "profile",
          "test.SET_CONFIG",
          Bundle(),
          sendResult = SEND_RESULT_COMPLETE,
        ),
      ),
      onSuccess = { completed = true },
      onError = { throw AssertionError(it) },
    )

    val result = resultFor(sent.single())
      .putExtra(
        "RESULT_LIST",
        arrayListOf(
          Bundle().apply { putString("RESULT", "SUCCESS") },
          Bundle().apply { putString("RESULT", "SUCCESS") },
        ),
      )

    assertTrue(coordinator.handleResult(result))
    assertTrue(completed)
  }

  @Test
  fun handleResult_rejectsCompleteResultWhenAnyProfileModuleFails() {
    val sent = mutableListOf<Intent>()
    var error: String? = null
    val coordinator = coordinator(sent)
    coordinator.enqueue(
      listOf(
        DataWedgeCommand(
          "profile",
          "test.SET_CONFIG",
          Bundle(),
          sendResult = SEND_RESULT_COMPLETE,
        ),
      ),
      onError = { error = it },
    )

    val result = resultFor(sent.single())
      .putExtra(
        "RESULT_LIST",
        arrayListOf(
          Bundle().apply { putString("RESULT", "SUCCESS") },
          Bundle().apply {
            putString("RESULT", "FAILURE")
            putString("RESULT_CODE", "PLUGIN_BUNDLE_INVALID")
          },
        ),
      )

    assertTrue(coordinator.handleResult(result))
    assertTrue(error!!.contains("PLUGIN_BUNDLE_INVALID"))
  }

  @Test
  fun handleResult_acceptsConfiguredIdempotentFailureInCompleteResult() {
    val sent = mutableListOf<Intent>()
    var completed = false
    val coordinator = coordinator(sent)
    coordinator.enqueue(
      listOf(
        DataWedgeCommand(
          "profile",
          "test.SET_CONFIG",
          Bundle(),
          acceptedFailureCodes = setOf("APP_ALREADY_ASSOCIATED"),
          sendResult = SEND_RESULT_COMPLETE,
        ),
      ),
      onSuccess = { completed = true },
      onError = { throw AssertionError(it) },
    )

    val result = resultFor(sent.single())
      .putExtra(
        "RESULT_LIST",
        arrayListOf(
          Bundle().apply { putString("RESULT", "SUCCESS") },
          Bundle().apply {
            putString("RESULT", "FAILURE")
            putString("RESULT_CODE", "APP_ALREADY_ASSOCIATED")
          },
        ),
      )

    assertTrue(coordinator.handleResult(result))
    assertTrue(completed)
  }

  @Test
  fun enqueue_delaysNextCommandForApiWithoutCorrelatedResults() {
    val sent = mutableListOf<Intent>()
    val scheduled = mutableListOf<Runnable>()
    val coordinator = DataWedgeCommandCoordinator(
      sendIntent = sent::add,
      scheduleTimeout = { runnable, _ -> scheduled += runnable },
      cancelTimeout = {},
      commandIdentifier = { "ledger-${sent.size + 1}" },
    )
    coordinator.enqueue(
      listOf(
        DataWedgeCommand(
          "notification",
          "test.REGISTER",
          Bundle(),
          completionDelayMs = 600L,
        ),
        DataWedgeCommand("status", "test.GET_STATUS", ""),
      ),
    )

    assertEquals(1, sent.size)
    scheduled.single().run()
    assertEquals(2, sent.size)
  }

  @Test
  fun enqueue_delaysNextCommandAfterCorrelatedResultWhenRequested() {
    val sent = mutableListOf<Intent>()
    val scheduled = mutableListOf<Pair<Runnable, Long>>()
    val coordinator = DataWedgeCommandCoordinator(
      sendIntent = sent::add,
      scheduleTimeout = { runnable, delay -> scheduled += runnable to delay },
      cancelTimeout = {},
      commandIdentifier = { "ledger-${sent.size + 1}" },
    )
    coordinator.enqueue(
      listOf(
        DataWedgeCommand(
          "profile",
          "test.SET_CONFIG",
          Bundle(),
          postCompletionDelayMs = 750L,
        ),
        DataWedgeCommand("status", "test.GET_STATUS", ""),
      ),
    )

    assertTrue(coordinator.handleResult(successFor(sent.single())))
    assertEquals(1, sent.size)
    val postCompletion = scheduled.last()
    assertEquals(750L, postCompletion.second)
    postCompletion.first.run()
    assertEquals(2, sent.size)
  }

  private fun coordinator(sent: MutableList<Intent>) =
    DataWedgeCommandCoordinator(
      sendIntent = sent::add,
      scheduleTimeout = { _, _ -> },
      cancelTimeout = {},
      commandIdentifier = { "ledger-${sent.size + 1}" },
    )

  private fun successFor(command: Intent) =
    resultFor(command)
      .putExtra("RESULT", "SUCCESS")

  private fun resultFor(command: Intent) =
    Intent("com.symbol.datawedge.api.RESULT_ACTION")
      .putExtra(
        "COMMAND_IDENTIFIER",
        command.getStringExtra("COMMAND_IDENTIFIER"),
      )
}
