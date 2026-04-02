package com.suguna.rtc.chatroom

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.suguna.rtc.R
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.app.Dialog
import android.view.Window

data class MusicTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val duration: Long,
    val uri: Uri,
    val albumArt: Uri?
)

class MusicViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val clInner: ConstraintLayout = view.findViewById(R.id.clMusicItemInner)
    val tvName: TextView = view.findViewById(R.id.tvMusicName)
    val tvDuration: TextView = view.findViewById(R.id.tvMusicDuration)
    val ivArt: ImageView = view.findViewById(R.id.ivMusicArt)
    val ivStatus: ImageView = view.findViewById(R.id.ivPlayStatus)
}

class MusicSelectionActivity : AppCompatActivity() {

    private lateinit var rvMusicList: RecyclerView
    private val fullMusicList = mutableListOf<MusicTrack>()
    private val filteredMusicList = mutableListOf<MusicTrack>()
    private lateinit var adapter: MusicAdapter
    
    private var selectedTrack: MusicTrack? = null
    private var currentPlayingUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_selection)

        currentPlayingUri = intent.getStringExtra("CURRENT_PLAYING_URI")

        rvMusicList = findViewById(R.id.rvMusicList)
        rvMusicList.layoutManager = LinearLayoutManager(this)
        adapter = MusicAdapter(filteredMusicList) { track ->
            updateMiniPlayer(track)
        }
        rvMusicList.adapter = adapter

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        
        val etSearch = findViewById<EditText>(R.id.etSearchMusic)
        findViewById<ImageView>(R.id.btnToggleSearch).setOnClickListener {
            if (etSearch.visibility == View.VISIBLE) {
                etSearch.visibility = View.GONE
                etSearch.setText("")
            } else {
                etSearch.visibility = View.VISIBLE
                etSearch.requestFocus()
            }
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterSongs(s.toString())
            }
        })

        findViewById<View>(R.id.btnPlayPause).setOnClickListener {
            playTrack(selectedTrack)
        }

        findViewById<View>(R.id.btnNext).setOnClickListener {
            playNext()
        }

        findViewById<View>(R.id.btnPrev).setOnClickListener {
            playPrevious()
        }

        checkPermissions()
    }

    private var loadingDialog: Dialog? = null

    private fun showLoadingDialog() {
        if (loadingDialog != null && loadingDialog?.isShowing == true) return
        loadingDialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            val layout = android.widget.LinearLayout(this@MusicSelectionActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setPadding(80, 80, 80, 80)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#CC121212")) // Dark glassmorphism
                    cornerRadius = 60f
                    setStroke(3, android.graphics.Color.parseColor("#80FFFFFF"))
                }
            }
            val progress = android.widget.ProgressBar(this@MusicSelectionActivity).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#00E676"))
                }
            }
            val text = android.widget.TextView(this@MusicSelectionActivity).apply {
                text = "Preparing High Quality Audio\nPlease Wait..."
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 40, 0, 0)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            layout.addView(progress)
            layout.addView(text)
            
            setContentView(layout)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setCancelable(false)
        }
        loadingDialog?.show()
    }

    private fun dismissLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun playTrack(track: MusicTrack?) {
        track?.let {
            showLoadingDialog()
            findViewById<View>(R.id.btnPlayPause).isEnabled = false
            findViewById<View>(R.id.btnNext).isEnabled = false
            findViewById<View>(R.id.btnPrev).isEnabled = false
            
            CoroutineScope(Dispatchers.Main).launch {
                val publicUrl = com.suguna.rtc.utils.CloudflareMusicUploader.uploadMusic(this@MusicSelectionActivity, it.uri)
                dismissLoadingDialog()
                if (publicUrl != null && !publicUrl.startsWith("ERROR")) {
                    val playIntent = Intent("com.suguna.rtc.ACTION_PLAY_MUSIC")
                    playIntent.putExtra("SONG_NAME", it.title)
                    playIntent.putExtra("SONG_URI", publicUrl)  // Pass Cloud URL instead of local URI
                    playIntent.putExtra("SONG_ART", it.albumArt.toString())
                    playIntent.putExtra("SONG_DURATION", it.duration)
                    playIntent.putExtra("ACTION", "TOGGLE")
                    sendBroadcast(playIntent)
                    finish()
                } else {
                    val errorMsg = if (publicUrl?.startsWith("ERROR") == true) publicUrl else "Failed to prep song. Please try again."
                    Toast.makeText(this@MusicSelectionActivity, errorMsg, Toast.LENGTH_LONG).show()
                    findViewById<View>(R.id.btnPlayPause).isEnabled = true
                    findViewById<View>(R.id.btnNext).isEnabled = true
                    findViewById<View>(R.id.btnPrev).isEnabled = true
                }
            }
        }
    }

    private fun playNext() {
        if (filteredMusicList.isEmpty()) return
        val currentIndex = filteredMusicList.indexOfFirst { it.uri == selectedTrack?.uri }
        val nextIndex = (currentIndex + 1) % filteredMusicList.size
        updateMiniPlayer(filteredMusicList[nextIndex])
    }

    private fun playPrevious() {
        if (filteredMusicList.isEmpty()) return
        val currentIndex = filteredMusicList.indexOfFirst { it.uri == selectedTrack?.uri }
        var prevIndex = currentIndex - 1
        if (prevIndex < 0) prevIndex = filteredMusicList.size - 1
        updateMiniPlayer(filteredMusicList[prevIndex])
    }

    private fun filterSongs(query: String) {
        filteredMusicList.clear()
        if (query.isEmpty()) {
            filteredMusicList.addAll(fullMusicList)
        } else {
            val q = query.lowercase()
            fullMusicList.forEach { if (it.title.lowercase().contains(q)) filteredMusicList.add(it) }
        }
        adapter.notifyDataSetChanged()
    }

    private fun checkPermissions() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 100)
        } else {
            loadMusic()
        }
    }

    private fun loadMusic() {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )

        val query = contentResolver.query(collection, projection, null, null, "${MediaStore.Audio.Media.TITLE} ASC")

        query?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val albCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            fullMusicList.clear()
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol)
                val artist = cursor.getString(artistCol)
                val duration = cursor.getLong(durCol)
                val albumId = cursor.getLong(albCol)

                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val artUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

                val track = MusicTrack(id, title, artist, duration, uri, artUri)
                fullMusicList.add(track)
                
                if (uri.toString() == currentPlayingUri) {
                    selectedTrack = track
                }
            }
            
            selectedTrack?.let { updateMiniPlayer(it) }
            filterSongs("")
        }
    }

    private fun updateMiniPlayer(track: MusicTrack) {
        selectedTrack = track
        findViewById<View>(R.id.cvBottomPlayer).visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvCurrentSongName).text = track.title
        findViewById<TextView>(R.id.tvCurrentSongDuration).text = formatDuration(track.duration)
        Glide.with(this).load(track.albumArt).placeholder(R.drawable.music_note_icon).into(findViewById(R.id.ivCurrentSongArt))
        adapter.notifyDataSetChanged()
    }

    private fun formatDuration(millis: Long): String {
        return String.format("%02d:%02d", TimeUnit.MILLISECONDS.toMinutes(millis),
            TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis)))
    }

    inner class MusicAdapter(private val tracks: List<MusicTrack>, private val onClick: (MusicTrack) -> Unit) :
        RecyclerView.Adapter<MusicViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MusicViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_music_track, parent, false)
            return MusicViewHolder(view)
        }

        override fun onBindViewHolder(holder: MusicViewHolder, position: Int) {
            val track = tracks[position]
            holder.tvName.text = track.title
            holder.tvDuration.text = formatDuration(track.duration)
            
            val isSelected = track.uri.toString() == selectedTrack?.uri.toString()
            if (isSelected) {
                holder.clInner.setBackgroundResource(R.drawable.bg_selected_music)
                holder.ivStatus.setImageResource(R.drawable.music_note_icon)
                holder.ivStatus.visibility = View.VISIBLE
            } else {
                holder.clInner.setBackgroundColor(Color.parseColor("#1AFFFFFF"))
                holder.ivStatus.setImageResource(R.drawable.play_arrow_icon)
                holder.ivStatus.visibility = View.VISIBLE
            }

            Glide.with(holder.itemView.context).load(track.albumArt).placeholder(R.drawable.music_note_icon).into(holder.ivArt)
            holder.itemView.setOnClickListener { onClick(track) }
        }

        override fun getItemCount() = tracks.size
    }
}
