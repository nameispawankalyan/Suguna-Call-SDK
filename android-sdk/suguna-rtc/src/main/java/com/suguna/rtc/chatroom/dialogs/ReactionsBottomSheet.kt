package com.suguna.rtc.chatroom.dialogs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.suguna.rtc.R
import com.suguna.rtc.chatroom.ReactionModel
import com.suguna.rtc.utils.Encryption

class ReactionsBottomSheet(
    private val onReactionSelected: (ReactionModel) -> Unit
) : BottomSheetDialogFragment() {

    private val reactionsList = mutableListOf<ReactionModel>()
    private lateinit var adapter: ReactionsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.BottomSheetDialogTheme)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val v = inflater.inflate(R.layout.dialog_reactions_bottom_sheet, container, false)
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rvReactions = view.findViewById<RecyclerView>(R.id.rvReactions)
        adapter = ReactionsAdapter(reactionsList) { reaction ->
            onReactionSelected(reaction)
            dismiss()
        }
        rvReactions.layoutManager = GridLayoutManager(requireContext(), 4)
        rvReactions.adapter = adapter

        loadReactions()
    }

    private fun loadReactions() {
        // Path matches Util.kt in Admin app: "VirtualReactions"
        val ref = FirebaseDatabase.getInstance().reference.child("VirtualReactions")
        ref.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                reactionsList.clear()
                for (child in snapshot.children) {
                    try {
                        val encId = child.child("ID").getValue(String::class.java) ?: ""
                        val encType = child.child("Type").getValue(String::class.java) ?: ""
                        val encName = child.child("Name").getValue(String::class.java) ?: ""
                        val encUrl = child.child("Url").getValue(String::class.java) ?: ""

                        val id = Encryption.decrypt(encId) ?: ""
                        val type = Encryption.decrypt(encType) ?: ""
                        val name = Encryption.decrypt(encName) ?: ""
                        val url = Encryption.decrypt(encUrl) ?: ""

                        if (id.isNotEmpty()) {
                            reactionsList.add(ReactionModel(id, name, type, url))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                adapter.notifyDataSetChanged()
            }

            override fun onCancelled(error: DatabaseError) {}
        })
    }

    class ReactionsAdapter(
        private val list: List<ReactionModel>,
        private val onClick: (ReactionModel) -> Unit
    ) : RecyclerView.Adapter<ReactionsAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_reaction_cell, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            
            if (item.type == "Emoji") {
                holder.image.visibility = View.VISIBLE
                holder.lottie.visibility = View.GONE
                Glide.with(holder.itemView.context).load(item.url).into(holder.image)
            } else {
                holder.image.visibility = View.GONE
                holder.lottie.visibility = View.VISIBLE
                holder.lottie.setAnimationFromUrl(item.url)
                holder.lottie.playAnimation()
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = list.size

        class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val image: ShapeableImageView = v.findViewById(R.id.ivReactionItem)
            val lottie: LottieAnimationView = v.findViewById(R.id.lottieReactionItem)
        }
    }
}
