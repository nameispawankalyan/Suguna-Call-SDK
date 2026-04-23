package com.suguna.rtc.chatroom.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.suguna.rtc.R
import com.suguna.rtc.chatroom.GiftModel
import com.suguna.rtc.chatroom.QuickReceiverAdapter
import com.suguna.rtc.chatroom.SeatParticipant

class QuickGiftBottomSheet(
    private val gift: GiftModel,
    private val seatedUsers: List<SeatParticipant>,
    private val roomOwnerId: String? = null,
    private val onSend: (GiftModel, List<String>, Int) -> Unit
) : BottomSheetDialogFragment() {

    private var multiplier = 1
    private val selectedReceiverIds = mutableSetOf<String>().apply {
        addAll(seatedUsers.map { it.id })
    }

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.dialog_quick_gift, container, false)

        val ivSelectedGift = view.findViewById<ImageView>(R.id.ivSelectedGift)
        val lottieSelectedGift = view.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.lottieSelectedGift)
        val tvSelectedGiftName = view.findViewById<TextView>(R.id.tvSelectedGiftName)
        val rvReceivers = view.findViewById<RecyclerView>(R.id.rvReceivers)
        val btnMinus = view.findViewById<ImageButton>(R.id.btnMinus)
        val btnPlus = view.findViewById<ImageButton>(R.id.btnPlus)
        val tvMultiplierValue = view.findViewById<TextView>(R.id.tvMultiplierValue)
        val tvTotalCost = view.findViewById<TextView>(R.id.tvTotalCost)
        val btnSend = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnQuickSend)

        // Setup Gift Info
        tvSelectedGiftName.text = gift.name
        val url = gift.image.lowercase()
        if (gift.type == "Lottie" || url.contains(".json") || url.contains(".lottie")) {
            ivSelectedGift.visibility = View.GONE
            lottieSelectedGift.visibility = View.VISIBLE
            lottieSelectedGift.setAnimationFromUrl(gift.image)
            lottieSelectedGift.playAnimation()
        } else {
            ivSelectedGift.visibility = View.VISIBLE
            lottieSelectedGift.visibility = View.GONE
            Glide.with(this).load(gift.image).into(ivSelectedGift)
        }

        // Setup Receivers
        rvReceivers.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        val adapter = QuickReceiverAdapter(seatedUsers, roomOwnerId, selectedReceiverIds) { selectedList ->
            selectedReceiverIds.clear()
            selectedReceiverIds.addAll(selectedList)
            updateTotal(tvTotalCost)
        }
        rvReceivers.adapter = adapter

        // Setup Multiplier
        btnMinus.setOnClickListener {
            if (multiplier > 1) {
                multiplier--
                tvMultiplierValue.text = multiplier.toString()
                updateTotal(tvTotalCost)
            }
        }
        btnPlus.setOnClickListener {
            multiplier++
            tvMultiplierValue.text = multiplier.toString()
            updateTotal(tvTotalCost)
        }

        btnSend.setOnClickListener {
            if (selectedReceiverIds.isEmpty()) {
                android.widget.Toast.makeText(requireContext(), "Please select at least one receiver!", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onSend(gift, selectedReceiverIds.toList(), multiplier)
            dismiss()
        }

        updateTotal(tvTotalCost)
        return view
    }

    private fun updateTotal(tv: TextView) {
        val total = gift.price * multiplier * selectedReceiverIds.size
        tv.text = "$total Coins"
    }
}
