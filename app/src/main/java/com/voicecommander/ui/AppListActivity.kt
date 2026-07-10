package com.voicecommander.ui

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.voicecommander.R
import com.voicecommander.databinding.ActivityAppListBinding
import com.voicecommander.managers.AppLockManager
import com.voicecommander.managers.PinManager

class AppListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppListBinding
    private lateinit var appLockManager: AppLockManager
    private lateinit var pinManager: PinManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appLockManager = AppLockManager(this)
        pinManager = PinManager(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        loadApps()
    }

    private fun loadApps() {
        val apps = appLockManager.getInstalledApps()
        val lockedApps = pinManager.getLockedApps()

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = AppListAdapter(apps, lockedApps) { app, isLocking ->
            if (isLocking) {
                showLockConfirmDialog(app)
            } else {
                showUnlockConfirmDialog(app)
            }
        }
    }

    private fun showLockConfirmDialog(app: AppLockManager.AppInfo) {
        AlertDialog.Builder(this)
            .setTitle("Lock ${app.appName}?")
            .setMessage(
                "This will:\n" +
                "• Force stop ${app.appName}\n" +
                "• Clear its cache/data\n" +
                "• Block it from opening until unlocked with your PIN\n\n" +
                "The app will be unusable until you unlock it."
            )
            .setPositiveButton("Lock") { _, _ ->
                val success = appLockManager.lockApp(app.packageName)
                val msg = if (success) "${app.appName} locked successfully" else "Partially locked ${app.appName}"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                loadApps()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUnlockConfirmDialog(app: AppLockManager.AppInfo) {
        if (!pinManager.isPinSet()) {
            Toast.makeText(this, "Please set a PIN first", Toast.LENGTH_SHORT).show()
            return
        }

        // Simple PIN confirmation
        val pinInput = android.widget.EditText(this).apply {
            hint = "Enter PIN to unlock"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Unlock ${app.appName}?")
            .setView(pinInput)
            .setPositiveButton("Unlock") { _, _ ->
                val pin = pinInput.text.toString()
                if (pinManager.verifyPin(pin)) {
                    val success = appLockManager.unlockApp(app.packageName)
                    val msg = if (success) "${app.appName} unlocked" else "Removed from lock list"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    loadApps()
                } else {
                    Toast.makeText(this, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class AppListAdapter(
        private val apps: List<AppLockManager.AppInfo>,
        private val lockedApps: Set<String>,
        private val onToggle: (AppLockManager.AppInfo, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.app_icon)
            val name: TextView = view.findViewById(R.id.app_name)
            val package: TextView = view.findViewById(R.id.app_package)
            val status: TextView = view.findViewById(R.id.app_status)
            val btnToggle: Button = view.findViewById(R.id.btn_toggle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            val isLocked = lockedApps.contains(app.packageName)

            holder.name.text = app.appName
            holder.package.text = app.packageName
            holder.status.text = if (isLocked) "🔒 LOCKED" else "🔓 Unlocked"
            holder.status.setTextColor(
                if (isLocked) getColor(R.color.locked_red)
                else getColor(R.color.unlocked_green)
            )

            app.icon?.let { holder.icon.setImageDrawable(it) }

            holder.btnToggle.text = if (isLocked) "Unlock" else "Lock"
            holder.btnToggle.setOnClickListener {
                onToggle(app, !isLocked)
            }
        }

        override fun getItemCount() = apps.size
    }


}
