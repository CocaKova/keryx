package chat.keryx.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

internal const val PACKAGE = "chat.keryx.app"

/**
 * What a person does in the first seconds: open the app, scroll the conversation back and
 * forth, open the drawer and scroll the sessions. On a signed-in device that is the transcript
 * and the drawer; on a fresh install it is the login screen, and the steps that find nothing
 * are skipped. Never types or sends anything, so recording a profile leaves no chats behind.
 */
internal fun MacrobenchmarkScope.keryxJourney() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), 5_000)
    device.waitForIdle()

    // A fresh install opens on the welcome: walk the demo instead, so the profile holds the chat
    // and the drawer and not only the sign-in form. The demo relaunches the app (this journey runs
    // in its own process, so that's fine), arrives with its tour up, and BACK ends the tour. The
    // demo is remembered, so later passes start in it.
    device.findObject(By.pkg(PACKAGE).text("Look around first"))?.let { demo ->
        demo.click()
        device.wait(Until.hasObject(By.pkg(PACKAGE).descStartsWith("Menu")), 15_000)
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
    }

    // The transcript is the screen's first scrollable list.
    device.findObject(By.pkg(PACKAGE).scrollable(true))?.let { list ->
        list.setGestureMargin(device.displayWidth / 5)
        repeat(2) {
            list.fling(Direction.UP)
            device.waitForIdle()
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }
    }

    // The drawer, through its Menu button: an edge swipe is the system BACK under gesture
    // navigation. Its description carries the unread count ("Menu, 3 unread").
    val menu = device.findObject(By.pkg(PACKAGE).descStartsWith("Menu")) ?: return
    menu.click()
    device.wait(Until.hasObject(By.desc("Close navigation menu")), 3_000)
    device.waitForIdle()
    device.findObject(By.pkg(PACKAGE).scrollable(true))?.let { list ->
        list.setGestureMargin(device.displayWidth / 5)
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
    device.pressBack()
    device.waitForIdle()
}
