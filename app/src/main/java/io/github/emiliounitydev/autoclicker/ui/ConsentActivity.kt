package io.github.emiliounitydev.autoclicker.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.emiliounitydev.autoclicker.Diag
import io.github.emiliounitydev.autoclicker.service.Consent
import io.github.emiliounitydev.autoclicker.service.RunnerService

/** Actividad invisible que vuelve a pedir el permiso de captura y se lo pasa
 * al servicio del panel (ver [Consent]). */
class ConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        startActivityForResult(Consent.captureIntent(this), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        Consent.disarm(this)
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, RunnerService::class.java).setAction(RunnerService.ACTION_RECAPTURE)
                .putExtra(RunnerService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(RunnerService.EXTRA_RESULT_DATA, data))
        } else Diag.log("captura", "permiso de captura NO concedido (automático)")
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
