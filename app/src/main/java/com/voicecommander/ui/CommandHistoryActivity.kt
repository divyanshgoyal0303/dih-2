package com.voicecommander.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.voicecommander.R
import com.voicecommander.databinding.ActivityCommandHistoryBinding
import com.voicecommander.managers.VoiceCommandManager
import java.text.SimpleDateFormat
import java.util.*

class CommandHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCommandHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommandHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        loadHistory()
    }

    private fun loadHistory() {
        val service = com.voicecommander.services.VoiceRecognitionService::class.java
        // We'll get history from a shared source
        val commandManager = VoiceCommandManager(this)
        val history = commandManager.getHistory()

        if (history.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerView.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerView.visibility = View.VISIBLE
            binding.recyclerView.layoutManager = LinearLayoutManager(this)
            binding.recyclerView.adapter = HistoryAdapter(history.reversed())
        }
    }

    inner class HistoryAdapter(
        private val entries: List<VoiceCommandManager.CommandHistoryEntry>
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val time: TextView = view.findViewById(R.id.entry_time)
            val spoken: TextView = view.findViewById(R.id.entry_spoken)
            val command: TextView = view.findViewById(R.id.entry_command)
            val result: TextView = view.findViewById(R.id.entry_result)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            val sdf = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())

            holder.time.text = sdf.format(Date(entry.timestamp))
            holder.spoken.text = "\"${entry.spokenText}\""
            holder.command.text = "Command: ${entry.recognizedCommand}"
            holder.result.text = entry.result

            holder.result.setTextColor(
                ContextCompat.getColor(holder.itemView.context,
                    if (entry.success) R.color.unlocked_green else R.color.locked_red
                )
            )
        }

        override fun getItemCount() = entries.size
    }
}
