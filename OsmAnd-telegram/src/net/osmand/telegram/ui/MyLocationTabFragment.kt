package net.osmand.telegram.ui

import android.animation.*
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.appbar.AppBarLayout
import net.osmand.PlatformUtil
import net.osmand.telegram.*
import net.osmand.telegram.helpers.LocationMessages
import net.osmand.telegram.helpers.TelegramHelper
import net.osmand.telegram.helpers.TelegramHelper.TelegramListener
import net.osmand.telegram.helpers.TelegramUiHelper
import net.osmand.telegram.utils.AndroidUtils
import net.osmand.telegram.utils.OsmandFormatter
import org.drinkless.td.libcore.telegram.TdApi
import java.util.*
import kotlin.Comparator
import kotlin.collections.HashSet

private const val SELECTED_CHATS_KEY = "selected_chats"
private const val SELECTED_CHATS_USERS = "selected_users"
private const val SUGGESTED = 2
private const val SHARE_LOCATION_CHAT = 1
private const val DEFAULT_CHAT = 0

private const val ADAPTER_UPDATE_INTERVAL_MS = 5 * 1000L // 5 sec

class MyLocationTabFragment : Fragment(), TelegramListener {

	private val log = PlatformUtil.getLog(MyLocationTabFragment::class.java)

	private var textMarginSmall: Int = 0
	private var textMarginBig: Int = 0
	private var searchBoxHeight: Int = 0
	private var searchBoxSidesMargin: Int = 0
	private var titlePaddingSmall: Int = 0
	private var titlePaddingBig: Int = 0

	private var appBarScrollRange: Int = -1

	private val app: TelegramApplication
		get() = activity?.application as TelegramApplication

	private val telegramHelper get() = app.telegramHelper
	private val shareLocationHelper get() = app.shareLocationHelper
	private val settings get() = app.settings

	private lateinit var appBarLayout: AppBarLayout
	private lateinit var imageContainer: FrameLayout
	private lateinit var currentUserIcon: ImageView
	private lateinit var textContainer: LinearLayout
	private lateinit var titleContainer: LinearLayout
	private lateinit var optionsBtn: ImageView
	private lateinit var title: TextView
	private lateinit var description: TextView
	private lateinit var searchBox: FrameLayout
	private lateinit var stopSharingSwitcher: Switch
	private lateinit var sharingStatusTitle: TextView
	private lateinit var sharingStatusIcon: ImageView
	private lateinit var startSharingBtn: View
	private lateinit var backToOsmAndBtn: TextView

	private lateinit var searchBoxBg: GradientDrawable

	private val adapter = MyLocationListAdapter()

	private var appBarCollapsed = false
	private lateinit var appBarOutlineProvider: ViewOutlineProvider

	private val selectedChats = HashSet<Long>()
	private val selectedUsers = HashSet<Long>()

	private var actionButtonsListener: ActionButtonsListener? = null

	private var sharingMode = false

	private var updateEnable: Boolean = false

	private lateinit var lastChatsInfo: LinkedList<TelegramSettings.LastChatInfo>

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View? {
		val activity = activity
		if (activity is ActionButtonsListener) {
			actionButtonsListener = activity
		}

		textMarginSmall = resources.getDimensionPixelSize(R.dimen.content_padding_standard)
		textMarginBig = resources.getDimensionPixelSize(R.dimen.my_location_text_sides_margin)
		searchBoxHeight = resources.getDimensionPixelSize(R.dimen.search_box_height)
		searchBoxSidesMargin = resources.getDimensionPixelSize(R.dimen.content_padding_half)
		titlePaddingSmall = resources.getDimensionPixelSize(R.dimen.app_bar_title_padding_small)
		titlePaddingBig = resources.getDimensionPixelSize(R.dimen.app_bar_title_padding_big)

		sharingMode = settings.hasAnyChatToShareLocation()

		savedInstanceState?.apply {
			val chatsArray = getLongArray(SELECTED_CHATS_KEY)
			val usersArray = getLongArray(SELECTED_CHATS_KEY)
			if (chatsArray != null) {
				selectedChats.addAll(chatsArray.toSet())
			}
			if (usersArray != null) {
				selectedUsers.addAll(usersArray.toSet())
			}
			actionButtonsListener?.switchButtonsVisibility((selectedUsers.isNotEmpty() || selectedChats.isNotEmpty()))
		}

		val mainView = inflater.inflate(R.layout.fragment_my_location_tab, container, false)

		appBarLayout = mainView.findViewById<AppBarLayout>(R.id.app_bar_layout).apply {
			if (Build.VERSION.SDK_INT >= 21) {
				appBarOutlineProvider = outlineProvider
				outlineProvider = null
			}
			addOnOffsetChangedListener(AppBarLayout.OnOffsetChangedListener { appBar, offset ->
				if (appBarScrollRange == -1) {
					appBarScrollRange = appBar.totalScrollRange
				}
				val collapsed = Math.abs(offset) == appBarScrollRange
				if (collapsed != appBarCollapsed) {
					appBarCollapsed = collapsed
					adjustText()
					adjustAppbar()
					adjustSearchBox()
					optionsBtn.visibility = if (collapsed) View.VISIBLE else View.GONE
				}
			})
		}

		currentUserIcon = mainView.findViewById(R.id.user_icon)

		optionsBtn = mainView.findViewById<ImageView>(R.id.options)
		with(activity as MainActivity) {
			setupOptionsBtn(optionsBtn)
			setupOptionsBtn(mainView.findViewById<ImageView>(R.id.options_title))
		}

		imageContainer = mainView.findViewById<FrameLayout>(R.id.image_container)
		titleContainer = mainView.findViewById<LinearLayout>(R.id.title_container).apply {
			AndroidUtils.addStatusBarPadding19v(context, this)
		}

		mainView.findViewById<TextView>(R.id.status_title).apply {
			val enabled = getString(R.string.shared_string_enabled)
			val sharingStatus = getString(R.string.location_sharing_status, enabled)
			val spannable = SpannableString(sharingStatus)
			val start = sharingStatus.indexOf(enabled)
			if (start != -1) {
				spannable.setSpan(ForegroundColorSpan(app.uiUtils.getActiveColor()), start, start + enabled.length, 0)
			}
			text = spannable
		}

		sharingStatusIcon = mainView.findViewById<ImageView>(R.id.sharing_status_icon)

		textContainer = mainView.findViewById<LinearLayout>(R.id.text_container).apply {
			if (Build.VERSION.SDK_INT >= 16) {
				layoutTransition.enableTransitionType(LayoutTransition.CHANGING)
			}
			title = findViewById(R.id.title)
			description = findViewById(R.id.description)
		}

		searchBoxBg = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			setColor(ContextCompat.getColor(app, R.color.screen_bg_light))
			cornerRadius = (searchBoxHeight / 2).toFloat()
		}

		searchBox = mainView.findViewById<FrameLayout>(R.id.search_box).apply {
			if (Build.VERSION.SDK_INT >= 16) {
				background = searchBoxBg
			} else {
				@Suppress("DEPRECATION")
				setBackgroundDrawable(searchBoxBg)
			}
			findViewById<View>(R.id.search_button).setOnClickListener {
				activity.supportFragmentManager?.also {
					SearchDialogFragment.showInstance(it, this@MyLocationTabFragment, selectedChats, selectedUsers)
				}
			}
			findViewById<ImageView>(R.id.search_icon)
				.setImageDrawable(app.uiUtils.getThemedIcon(R.drawable.ic_action_search_dark))
		}

		mainView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_view).apply {
			layoutManager = LinearLayoutManager(context)
			adapter = this@MyLocationTabFragment.adapter
			addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
				override fun onScrollStateChanged(recyclerView: androidx.recyclerview.widget.RecyclerView, newState: Int) {
					super.onScrollStateChanged(recyclerView, newState)
					when (newState) {
						androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_DRAGGING -> animateStartSharingBtn(false)
						androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE -> animateStartSharingBtn(true)
					}
				}
			})
		}

		mainView.findViewById<View>(R.id.stop_all_sharing_row).setOnClickListener {
			fragmentManager?.also { fm ->
				DisableSharingBottomSheet.showInstance(fm, this, adapter.items.size)
			}
		}

		mainView.findViewById<View>(R.id.sharing_status_container).setOnClickListener {
			settings.updateSharingStatusHistory()
			updateSharingStatus()
			fragmentManager?.also { fm ->
				SharingStatusBottomSheet.showInstance(fm, this)
			}
		}

		stopSharingSwitcher = mainView.findViewById(R.id.stop_all_sharing_switcher)

		sharingStatusTitle = mainView.findViewById(R.id.sharing_status_title)

		startSharingBtn = mainView.findViewById<View>(R.id.start_sharing_btn).apply {
			visibility = if (sharingMode) View.VISIBLE else View.GONE
			setOnClickListener {
				sharingMode = false
				actionButtonsListener?.switchButtonsVisibility(true)
				updateContent()
			}
		}

		backToOsmAndBtn = mainView.findViewById<TextView>(R.id.back_to_osmand)
		lastChatsInfo = settings.lastChatsInfo

		return mainView
	}

	override fun onResume() {
		super.onResume()
		updateCurrentUserPhoto()
		updateContent()
		updateEnable = true
		startHandler()
	}

	override fun onPause() {
		super.onPause()
		updateEnable = false
	}

	override fun onSaveInstanceState(outState: Bundle) {
		super.onSaveInstanceState(outState)
		outState.putLongArray(SELECTED_CHATS_KEY, selectedChats.toLongArray())
		outState.putLongArray(SELECTED_CHATS_USERS, selectedUsers.toLongArray())
	}

	override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
		super.onActivityResult(requestCode, resultCode, data)
		when (requestCode) {
			SetTimeDialogFragment.LOCATION_SHARED_REQUEST_CODE, SearchDialogFragment.SEARCH_ITEMS_REQUEST_CODE -> {
				sharingMode = settings.hasAnyChatToShareLocation()
				clearSelection()
				updateContent()
				if (resultCode == SetTimeDialogFragment.LOCATION_SHARED_REQUEST_CODE) {
					askBatteryOptimisation()
				}
			}
			DisableSharingBottomSheet.SHARING_DISABLED_REQUEST_CODE -> {
				saveChatsToLastChatsInfo()
				sharingMode = false
				app.stopSharingLocation()
				updateContent()
			}
			SharingStatusBottomSheet.SHARING_STATUS_REQUEST_CODE -> {
				updateSharingStatus()
			}
		}
	}

	override fun onTelegramStatusChanged(
		prevTelegramAuthorizationState: TelegramHelper.TelegramAuthorizationState,
		newTelegramAuthorizationState: TelegramHelper.TelegramAuthorizationState
	) {
		when (newTelegramAuthorizationState) {
			TelegramHelper.TelegramAuthorizationState.READY -> {
				updateContent()
			}
			TelegramHelper.TelegramAuthorizationState.LOGGING_OUT,
			TelegramHelper.TelegramAuthorizationState.CLOSED,
			TelegramHelper.TelegramAuthorizationState.UNKNOWN -> {
				adapter.items = mutableListOf()
			}
			else -> Unit
		}
	}

	override fun onTelegramChatsRead() {
		updateContent()
	}

	override fun onTelegramChatsChanged() {
		updateContent()
	}

	override fun onTelegramChatChanged(chat: TdApi.Chat) {
		updateContent()
	}

	override fun onTelegramChatCreated(chat: TdApi.Chat) {
		sharingMode = settings.hasAnyChatToShareLocation()
		updateContent()
	}

	override fun onTelegramUserChanged(user: TdApi.User) {
		if (user.id == telegramHelper.getCurrentUser()?.id) {
			updateCurrentUserPhoto()
		}
		updateContent()
	}

	override fun onTelegramError(code: Int, message: String) {
	}

	fun onPrimaryBtnClick() {
		if (selectedChats.isNotEmpty() || selectedUsers.isNotEmpty()) {
			val fm = fragmentManager ?: return
			SetTimeDialogFragment.showInstance(fm, selectedChats, selectedUsers, this)
		}
	}

	fun onSecondaryBtnClick() {
		clearSelection()
		if (settings.hasAnyChatToShareLocation()) {
			sharingMode = true
			updateContent()
		}
	}

	private fun askBatteryOptimisation() {
		if (sharingMode && !settings.batteryOptimisationAsked && Build.VERSION.SDK_INT >= 26) {
			fragmentManager?.also { fm ->
				BatteryOptimizationBottomSheet.showInstance(fm)
				settings.batteryOptimisationAsked = true
			}
		}
	}

	private fun updateCurrentUserPhoto() {
		TelegramUiHelper.setupPhoto(
			app,
			currentUserIcon,
			telegramHelper.getUserPhotoPath(telegramHelper.getCurrentUser()),
			R.drawable.img_user_placeholder,
			false
		)
	}

	private fun startHandler() {
		val updateAdapter = Handler()
		updateAdapter.postDelayed({
			if (updateEnable) {
				updateContent()
				startHandler()
			}
		}, ADAPTER_UPDATE_INTERVAL_MS)
	}

	private fun animateStartSharingBtn(show: Boolean) {
		if (startSharingBtn.visibility == View.VISIBLE) {
			val scale = if (show) 1f else 0f
			startSharingBtn.animate()
				.scaleX(scale)
				.scaleY(scale)
				.setDuration(200)
				.setInterpolator(LinearInterpolator())
				.start()
		}
	}

	private fun clearSelection() {
		selectedChats.clear()
		selectedUsers.clear()
		adapter.notifyDataSetChanged()
		actionButtonsListener?.switchButtonsVisibility(false)
	}

	private fun adjustText() {
		val gravity = if (appBarCollapsed) Gravity.START else Gravity.CENTER
		val padding = if (appBarCollapsed) textMarginSmall else textMarginBig
		val titlePadding = if (appBarCollapsed) titlePaddingBig else titlePaddingSmall
		textContainer.apply {
			setPadding(padding, paddingTop, padding, paddingBottom)
			if (appBarCollapsed) {
				AndroidUtils.addStatusBarPadding19v(app, this)
			} else {
				AndroidUtils.removeStatusBarPadding19v(app, this)
			}
		}
		title.apply {
			this.gravity = gravity
			setPadding(paddingLeft, titlePadding, paddingRight, titlePadding)
		}
		description.gravity = gravity
	}

	private fun adjustAppbar() {
		updateTitleTextColor()
		if (Build.VERSION.SDK_INT >= 21) {
			if (appBarCollapsed) {
				appBarLayout.outlineProvider = appBarOutlineProvider
			} else {
				appBarLayout.outlineProvider = null
			}
		}
	}

	private fun adjustSearchBox() {
		val cornerRadiusFrom = if (appBarCollapsed) searchBoxHeight / 2 else 0
		val cornerRadiusTo = if (appBarCollapsed) 0 else searchBoxHeight / 2
		val marginFrom = if (appBarCollapsed) searchBoxSidesMargin else 0
		val marginTo = if (appBarCollapsed) 0 else searchBoxSidesMargin

		val cornerAnimator = ObjectAnimator.ofFloat(
			searchBoxBg,
			"cornerRadius",
			cornerRadiusFrom.toFloat(),
			cornerRadiusTo.toFloat()
		)

		val marginAnimator = ValueAnimator.ofInt(marginFrom, marginTo)
		marginAnimator.addUpdateListener {
			val value = it.animatedValue as Int
			val params = searchBox.layoutParams as LinearLayout.LayoutParams
			params.setMargins(value, params.topMargin, value, params.bottomMargin)
			searchBox.layoutParams = params
		}

		AnimatorSet().apply {
			duration = 200
			playTogether(cornerAnimator, marginAnimator)
			addListener(object : AnimatorListenerAdapter() {
				override fun onAnimationEnd(animation: Animator?) {
					updateTitleTextColor()
					if (appBarCollapsed && Build.VERSION.SDK_INT >= 21) {
						appBarLayout.outlineProvider = appBarOutlineProvider
					}
				}
			})
			start()
		}

		if (!appBarCollapsed && Build.VERSION.SDK_INT >= 21) {
			appBarLayout.outlineProvider = null
		}
	}

	private fun updateTitleTextColor() {
		val color = if (appBarCollapsed) R.color.app_bar_title_light else R.color.ctrl_active_light
		context?.also {
			title.setTextColor(ContextCompat.getColor(it, color))
		}
	}

	private fun updateContent() {
		sharingMode = sharingMode && settings.hasAnyChatToShareLocation()
		updateSharingStatus()
		updateSharingMode()
		updateList()
	}

	private fun updateSharingMode() {
		val headerParams = imageContainer.layoutParams as AppBarLayout.LayoutParams
		imageContainer.visibility = if (sharingMode) View.GONE else View.VISIBLE
		textContainer.visibility = if (sharingMode) View.GONE else View.VISIBLE
		titleContainer.visibility = if (sharingMode) View.VISIBLE else View.GONE
		startSharingBtn.visibility = if (sharingMode) View.VISIBLE else View.GONE
		headerParams.scrollFlags =
				if (sharingMode) 0 else AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
		stopSharingSwitcher.isChecked = true
		appBarScrollRange = -1
		updateBackToOsmAndBtn()
	}

	private fun updateBackToOsmAndBtn() {
		val pckg = app.settings.appToConnectPackage
		backToOsmAndBtn.apply {
			visibility = if (pckg.isNotEmpty() && sharingMode && AndroidUtils.isAppInstalled(app, pckg)) {
				setOnClickListener {
					val startIntent = app.packageManager.getLaunchIntentForPackage(pckg)
					if (startIntent != null) {
						startIntent.addCategory(Intent.CATEGORY_LAUNCHER)
						startActivity(startIntent)
					}
				}
				View.VISIBLE
			} else {
				View.GONE
			}
		}
	}

	private fun updateSharingStatus() {
		if (sharingMode) {
			settings.updateSharingStatusHistory()
			val sharingStatus = settings.sharingStatusChanges.last()
			sharingStatusTitle.text = sharingStatus.getTitle(app)
			sharingStatusIcon.setImageDrawable(
				app.uiUtils.getIcon(
					sharingStatus.statusType.iconId,
					sharingStatus.statusType.iconColorRes
				)
			)
		}
	}

	private fun updateList() {
		val lastItems = getLastShareItems()
		val items: MutableList<Any> = mutableListOf()
		val chats: MutableList<TdApi.Chat> = mutableListOf()
		val contacts = telegramHelper.getContacts()
		val chatList = if (sharingMode) {
			settings.getShareLocationChats()
		} else {
			telegramHelper.getChatListIds()
		}
		for (chatId in chatList) {
			val chat = telegramHelper.getChat(chatId)
			if (chat != null) {
				if (!sharingMode && settings.isSharingLocationToChat(chatId)) {
					continue
				}
				chats.add(chat)
			}
		}
		items.addAll(chats)
		if (!sharingMode) {
			for (user in contacts.values) {
				val containsInChats = chats.any { telegramHelper.getUserIdFromChatType(it.type) == user.id }
				if ((!sharingMode && settings.isSharingLocationToUser(user.id)) || containsInChats) {
					continue
				}
				items.add(user)
			}
		}
		if (sharingMode && settings.hasAnyChatToShareLocation()) {
			val filteredLastItems = lastItems.filter { !settings.isSharingLocationToChat(it.chat.id) }.toMutableList()
			val sortedItems = sortAdapterItems(items as MutableList<TdApi.Object>)
			sortedItems.add(SuggestedChats(filteredLastItems))
			adapter.items = sortedItems
		} else {
			val filteredLastItems = lastItems.filter { !settings.isSharingLocationToChat(it.chat.id) }.toMutableList()
			items.add(0, SuggestedChats(filteredLastItems))
			adapter.items = items
		}
	}

	private fun getLastShareItems(): MutableList<LastChat> {
		val lastItems: MutableList<LastChat> = mutableListOf()
		val chatListIds = telegramHelper.getChatListIds()
		chatListIds.forEach { chatId ->
			val chat = telegramHelper.getChat(chatId)
			val lastInfo = lastChatsInfo.find { it.chatId == chatId }
			if (chat != null && lastInfo != null) {
				val index = lastChatsInfo.indexOf(lastInfo)
				lastItems.add(LastChat(chat, lastChatsInfo[index].period))
			}
		}
		return lastItems
	}

	private fun saveChatsToLastChatsInfo() {
		val chatListIds = settings.getShareLocationChats()
		chatListIds.forEach { id ->
			val shareInfo = settings.getChatsShareInfo()[id]
			if (shareInfo != null) {
				settings.addTimePeriodToLastItem(shareInfo.chatId, shareInfo.livePeriod)
			}
		}
	}

	private fun sortAdapterItems(list: MutableList<TdApi.Object>): MutableList<Any> {
		list.sortWith(Comparator<TdApi.Object> { o1, o2 ->
			val title1 = when (o1) {
				is TdApi.Chat -> o1.title
				is TdApi.User -> TelegramUiHelper.getUserName(o1)
				else -> ""
			}
			val title2 = when (o2) {
				is TdApi.Chat -> o2.title
				is TdApi.User -> TelegramUiHelper.getUserName(o2)
				else -> ""
			}
			title1.compareTo(title2)
		})
		return list.toMutableList()
	}

	inner class MyLocationListAdapter :
		androidx.recyclerview.widget.RecyclerView.Adapter<MyLocationListAdapter.BaseViewHolder>() {
		var items = mutableListOf<Any>()
			set(value) {
				field = value
				notifyDataSetChanged()
			}

		override fun getItemViewType(position: Int): Int {
			val item = items[position]
			val id = when (item) {
				is TdApi.Chat -> item.id
				is TdApi.User -> item.id.toLong()
				else -> -1
			}
			return if (item is SuggestedChats) {
				SUGGESTED
			} else if (settings.isSharingLocationToChat(id) && sharingMode) {
				SHARE_LOCATION_CHAT
			} else {
				DEFAULT_CHAT
			}
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
			return when (viewType) {
				SHARE_LOCATION_CHAT -> {
					val view = LayoutInflater.from(parent.context)
						.inflate(R.layout.my_location_sharing_chat, parent, false)
					SharingChatViewHolder(view)
				}
				DEFAULT_CHAT -> {
					val view = LayoutInflater.from(parent.context)
						.inflate(R.layout.user_list_item, parent, false)
					ChatViewHolder(view)
				}
				SUGGESTED -> {
					val view = LayoutInflater.from(parent.context)
							.inflate(R.layout.suggested_list_item, parent, false)
					SuggestedViewHolder(view)
				}
				else -> throw RuntimeException("Unsupported view type: $viewType")
			}
		}

		@SuppressLint("SetTextI18n")
		override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
			val item = items[position]
			val isChat = item is TdApi.Chat
			val itemId = if (isChat) {
				(item as TdApi.Chat).id
			} else if (item is TdApi.User) {
				item.id
			} else {
				-1
			}

			val lastItem = position == itemCount - 1
			val live = (isChat && settings.isSharingLocationToChat(itemId))
			val shareInfo = if (isChat) settings.getChatsShareInfo()[itemId] else null

			setupPhoto(item, holder.icon, isChat)
			holder.title?.text = getTitleText(item)

			if (holder is ChatViewHolder) {
				holder.description?.visibility = View.GONE
				if (live) {
					holder.checkBox?.visibility = View.GONE
				} else {
					holder.checkBox?.apply {
						visibility = View.VISIBLE
						setOnCheckedChangeListener(null)
						isChecked = if (isChat) {
							selectedChats.contains(itemId)
						} else {
							selectedUsers.contains(itemId)
						}
						setOnCheckedChangeListener { _, isChecked ->
							if (isChecked) {
								if (isChat) {
									selectedChats.add(itemId)
								} else {
									selectedUsers.add(itemId)
								}
							} else {
								if (isChat) {
									selectedChats.remove(itemId)
								} else {
									selectedUsers.remove(itemId)
								}
							}
							actionButtonsListener?.switchButtonsVisibility(selectedChats.isNotEmpty() || selectedUsers.isNotEmpty())
						}
					}
				}
				holder.topShadowDivider?.visibility = View.GONE
				holder.bottomShadow?.visibility = if (lastItem) View.VISIBLE else View.GONE
				holder.itemView.setOnClickListener {
					if (live) {
						settings.shareLocationToChat(itemId, false)
						shareLocationHelper.stopSharingLocation()
						notifyItemChanged(position)
					} else {
						holder.checkBox?.apply {
							isChecked = !isChecked
						}
					}
				}
			} else if (holder is SharingChatViewHolder) {
				holder.switcher?.apply {
					isChecked = live
					setOnCheckedChangeListener { _, isChecked ->
						if (!isChecked) {
							settings.shareLocationToChat(itemId, false)
							if (shareInfo != null) {
								telegramHelper.stopSendingLiveLocationToChat(shareInfo)
								settings.addTimePeriodToLastItem(shareInfo.chatId,shareInfo.livePeriod)
							}
							removeItem(item as TdApi.Object)
						}
					}
				}

				val duration = shareInfo?.userSetLivePeriod
				if (duration != null && duration > 0) {
					holder.descriptionDuration?.text = OsmandFormatter.getFormattedDuration(app, duration)
					holder.description?.apply {
						visibility = View.VISIBLE
						text = "${getText(R.string.sharing_time)}:"
					}
				}

				val expiresIn = shareInfo?.getChatLiveMessageExpireTime() ?: 0

				holder.textInArea?.apply {
					val time =
						shareInfo?.additionalActiveTime ?: ADDITIONAL_ACTIVE_TIME_VALUES_SEC[0]
					visibility = View.VISIBLE
					text = "+ ${OsmandFormatter.getFormattedDuration(app, time)}"
					setOnClickListener {
						val expireTime = shareInfo?.getChatLiveMessageExpireTime() ?: 0
						val newLivePeriod = expireTime + (shareInfo?.additionalActiveTime
							?: ADDITIONAL_ACTIVE_TIME_VALUES_SEC[0])
						val nextAdditionalActiveTime = shareInfo?.getNextAdditionalActiveTime()
							?: ADDITIONAL_ACTIVE_TIME_VALUES_SEC[1]
						if (isChat) {
							settings.shareLocationToChat(
								itemId,
								true,
								newLivePeriod,
								nextAdditionalActiveTime
							)
						} else {
							settings.shareLocationToUser(
								itemId,
								newLivePeriod,
								nextAdditionalActiveTime
							)
						}
						notifyItemChanged(position)
					}
				}

				holder.sharingExpiresLine?.apply {
					visibility = if (expiresIn > 0) View.VISIBLE else View.GONE
					val description = SpannableStringBuilder(getText(R.string.expire_at))
					val start = description.length
					description.append(" ${OsmandFormatter.getFormattedTime(expiresIn * 1000)} ")
					description.setSpan(StyleSpan(Typeface.BOLD), start, description.length, 0)
					description.setSpan(ForegroundColorSpan(ContextCompat.getColor(app, R.color.primary_text_light)), start, description.length, 0)
					description.append((getString(R.string.in_time, OsmandFormatter.getFormattedDuration(app, expiresIn, true))))
					text = description
				}

				holder.gpsPointsLine?.apply {
					visibility = if (app.settings.showGpsPoints && shareInfo != null) View.VISIBLE else View.GONE
					if (shareInfo != null) {
						val description = SpannableStringBuilder("${getText(R.string.gps_points)}:")
						val bufferedPoints = if (app.settings.shareTypeValue == SHARE_TYPE_MAP) {
							shareInfo.pendingTdLibMap + app.locationMessages.getBufferedMessagesCountForChat(shareInfo.chatId, LocationMessages.TYPE_MAP)
						} else {
							shareInfo.pendingTdLibText + app.locationMessages.getBufferedMessagesCountForChat(shareInfo.chatId, LocationMessages.TYPE_TEXT)
						}
						val start = description.length
						description.append(" ${shareInfo.sentMessages} ")
						description.setSpan(StyleSpan(Typeface.BOLD), start, description.length, 0)
						description.setSpan(ForegroundColorSpan(ContextCompat.getColor(app, R.color.primary_text_light)), start, description.length, 0)
						description.append(getString(R.string.gps_points_in_buffer, bufferedPoints))
						text = description
					}
				}
			} else if (holder is SuggestedViewHolder) {
				holder.list.removeAllViews()
				if ((item as SuggestedChats).list.isEmpty()) {
					holder.container?.visibility = View.GONE
				} else {
					holder.container?.visibility = View.VISIBLE
					val iterator = item.list.iterator()
					iterator.forEach {
						holder.list.addView(createLastChatView(it, iterator.hasNext()))
					}
				}
				val tv = TypedValue()
				if (!sharingMode) {
					holder.dividerTop?.visibility = View.GONE
					holder.dividerBottom?.visibility = View.VISIBLE
					holder.header?.visibility = View.GONE
					if (context?.theme?.resolveAttribute(R.attr.card_bg_color, tv, true) != null) {
						holder.container?.setBackgroundColor(tv.data)
					}
				} else {
					holder.dividerTop?.visibility = View.VISIBLE
					holder.dividerBottom?.visibility = View.GONE
					holder.header?.visibility = View.VISIBLE
					if (context?.theme?.resolveAttribute(R.attr.shared_chat_card_bg, tv, true) != null) {
						holder.container?.setBackgroundResource(tv.resourceId)
					}
				}
			}
		}

		private fun getTitleText(item: Any): String? {
			val currentUserId = telegramHelper.getCurrentUserId()
			return when (item) {
				is LastChat -> {
					if (telegramHelper.isPrivateChat(item.chat) && (item.chat.type as TdApi.ChatTypePrivate).userId == currentUserId) {
						getString(R.string.saved_messages)
					} else {
						item.chat.title
					}
				}
				is TdApi.Chat -> {
					if (telegramHelper.isPrivateChat(item) && (item.type as TdApi.ChatTypePrivate).userId == currentUserId) {
						getString(R.string.saved_messages)
					} else {
						item.title
					}
				}
				is TdApi.User -> {
					if (item.id == currentUserId) getString(R.string.saved_messages) else TelegramUiHelper.getUserName(item)
				}
				else -> null
			}
		}

		private fun setupPhoto(item: Any, icon: ImageView?, isChat: Boolean) {
			val photoPath = when (item) {
				is LastChat -> item.chat.photo?.small?.local?.path
				is TdApi.Chat -> item.photo?.small?.local?.path
				is TdApi.User -> item.profilePhoto?.small?.local?.path
				else -> null
			}
			val placeholderId =
					if (isChat && telegramHelper.isGroup(item as TdApi.Chat)) R.drawable.img_group_picture else R.drawable.img_user_picture

			TelegramUiHelper.setupPhoto(app, icon, photoPath, placeholderId, false)
		}

		private fun createLastChatView(lastChat: LastChat, hasNext: Boolean): View {
			val view = layoutInflater.inflate(R.layout.last_share_list_item, null)
			val time: TextView = view.findViewById(R.id.time)
			val container: LinearLayout = view.findViewById(R.id.container)
			val icon: ImageView = view.findViewById(R.id.icon)
			val title: TextView = view.findViewById(R.id.title)
			val divider: View = view.findViewById(R.id.divider)

			if (sharingMode && hasNext) {
				divider.visibility = View.VISIBLE
			}

			container.setOnClickListener {
				if (!AndroidUtils.isLocationPermissionAvailable(view!!.context)) {
					AndroidUtils.requestLocationPermission(activity!!)
				} else {
					settings.shareLocationToChat(lastChat.chat.id, true, lastChat.time)
					app.shareLocationHelper.startSharingLocation()
					(activity as MainActivity).refreshPages()
				}
			}

			title.text = getTitleText(lastChat.chat)
			setupPhoto(lastChat.chat, icon, true)
			icon.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0F) })

			val sharingTime = SpannableStringBuilder("${getString(R.string.sharing_time)}: ")
			val formattedTime = OsmandFormatter.getFormattedDuration(app, lastChat.time, false)
			val start = sharingTime.length
			sharingTime.append(formattedTime)
			sharingTime.setSpan(StyleSpan(Typeface.BOLD), start, sharingTime.length, 0)
			sharingTime.setSpan(ForegroundColorSpan(ContextCompat.getColor(app, R.color.ctrl_active_light)), start, sharingTime.length, 0)
			time.text = sharingTime
			return view
		}

		private fun removeItem(chat: TdApi.Object) {
			items.remove(chat)
			val filtered = items.filterIsInstance<TdApi.Object>()
			if (filtered.isEmpty()) {
				sharingMode = false
				updateContent()
				shareLocationHelper.stopSharingLocation()
			} else {
				adapter.notifyDataSetChanged()
			}
		}

		override fun getItemCount() = items.size

		abstract inner class BaseViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
			val icon: ImageView? = view.findViewById(R.id.icon)
			val title: TextView? = view.findViewById(R.id.title)
			val description: TextView? = view.findViewById(R.id.description)
			val textInArea: TextView? = view.findViewById(R.id.text_in_area)
		}

		inner class ChatViewHolder(val view: View) : BaseViewHolder(view) {
			val checkBox: CheckBox? = view.findViewById(R.id.check_box)
			val topShadowDivider: View? = view.findViewById(R.id.top_divider)
			val bottomShadow: View? = view.findViewById(R.id.bottom_shadow)
		}

		inner class SharingChatViewHolder(val view: View) : BaseViewHolder(view) {
			val descriptionDuration: TextView? = view.findViewById(R.id.duration)
			val switcher: Switch? = view.findViewById(R.id.switcher)
			val sharingExpiresLine: TextView? = view.findViewById(R.id.expires_line)
			val gpsPointsLine: TextView? = view.findViewById(R.id.gps_points_line)
		}

		inner class SuggestedViewHolder(val view: View) : BaseViewHolder(view) {
			val list: LinearLayout = view.findViewById(R.id.last_items_list)
			val container: LinearLayout? = view.findViewById(R.id.container)
			val dividerBottom: View? = view.findViewById(R.id.divider_bottom)
			val dividerTop: View? = view.findViewById(R.id.divider_top)
			val header: TextView? = view.findViewById(R.id.header)
		}
	}

	interface ActionButtonsListener {
		fun switchButtonsVisibility(visible: Boolean)
	}
}

class LastChat internal constructor(val chat: TdApi.Chat, val time: Long)

class SuggestedChats internal constructor(val list: MutableList<LastChat>)
