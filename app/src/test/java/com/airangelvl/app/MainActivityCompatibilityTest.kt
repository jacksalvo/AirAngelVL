package com.airangelvl.app

import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Inflate the real Hilt activity on each API boundary without a camera or native USB calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28, 29, 30, 33, 34], application = AirAngelApplication::class)
class MainActivityCompatibilityTest {
    @Before fun grantInstallTimeReceiverPermission() {
        val application = ApplicationProvider.getApplicationContext<AirAngelApplication>()
        // Android grants the manifest's self-signature permission at install time; Robolectric does not.
        shadowOf(application).grantPermissions("${application.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    @Test fun `launch and leave without a camera is safe`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        assertNotNull(activity.findViewById<TextView>(R.id.status_text))
        assertFalse(activity.findViewById<Button>(R.id.capture_button).isEnabled)
        assertFalse(activity.findViewById<Button>(R.id.record_toggle).isEnabled)
        controller.pause().stop().destroy()
    }
}
