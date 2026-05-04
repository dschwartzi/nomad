package ai.nomad

import ai.nomad.ui.NomadApp as NomadComposeApp
import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val permissionsState = mutableStateOf(false)
    private val isDefaultState = mutableStateOf(false)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsState.value = hasAllSmsPermissions()
    }

    private val requestDefaultSms = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isDefaultState.value = isDefaultSmsApp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionsState.value = hasAllSmsPermissions()
        isDefaultState.value = isDefaultSmsApp()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    NomadComposeApp(
                        app = application as NomadApp,
                        hasPermissions = permissionsState,
                        isDefaultSms = isDefaultState,
                        requestPermissions = { requestPermissions.launch(REQUIRED_PERMISSIONS) },
                        requestDefaultSms = { launchDefaultSmsChooser() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionsState.value = hasAllSmsPermissions()
        isDefaultState.value = isDefaultSmsApp()
    }

    private fun hasAllSmsPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isDefaultSmsApp(): Boolean =
        Telephony.Sms.getDefaultSmsPackage(this) == packageName

    private fun launchDefaultSmsChooser() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                val intent = rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
                requestDefaultSms.launch(intent)
                return
            }
        }
        @Suppress("DEPRECATION")
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        }
        requestDefaultSms.launch(intent)
    }

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }
}
