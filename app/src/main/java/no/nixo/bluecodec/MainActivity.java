package no.nixo.bluecodec;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.json.*;

public class MainActivity extends AppCompatActivity {
  private CodecController controller;
  private boolean resumeAfterPermission = true;
  private LinearLayout content;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private String selected = "", rendered = "";
  private final DeviceSelection deviceSelection = new DeviceSelection();
  private int ink, muted, accent, soft, surface;
  private final java.util.Set<String> codecHelp = new java.util.HashSet<>();
  private final Runnable update = this::render;
  private final Runnable poll =
      new Runnable() {
        public void run() {
          controller.refresh(false);
          handler.postDelayed(this, 3000);
        }
      };

  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    controller = (CodecController) getApplication();
    ink = getColor(R.color.ink);
    muted = getColor(R.color.muted);
    accent = getColor(R.color.accent);
    soft = getColor(R.color.soft);
    surface = getColor(R.color.surface);
    selected = controller.prefs.store.getString("selected", "");
    boolean dark =
        (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
        .setAppearanceLightStatusBars(!dark);
    WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
        .setAppearanceLightNavigationBars(!dark);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(true);
    scroll.setBackgroundColor(getColor(R.color.page));
    content = column();
    content.setPadding(dp(20), dp(16), dp(20), dp(28));
    scroll.addView(content);
    setContentView(scroll);
    scroll.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
          view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    scroll.requestApplyInsets();
    render();
    controller.connect();
  }

  int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  LinearLayout row() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  void gap(LinearLayout parent, int size) {
    parent.addView(new Space(this), new LinearLayout.LayoutParams(1, dp(size)));
  }

  TextView text(LinearLayout parent, String label, int size, int color, boolean bold) {
    TextView v = new TextView(this);
    v.setText(label);
    v.setTextColor(color);
    v.setTextSize(size);
    v.setFontFeatureSettings("kern");
    if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    v.setLineSpacing(dp(2), 1);
    parent.addView(v);
    return v;
  }

  ImageView avatar(String id, int size) {
    ImageView v = new ImageView(this);
    v.setImageDrawable(new DeviceAvatar(id));
    v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    v.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
    return v;
  }

  MaterialButton button(
      LinearLayout parent, String label, boolean primary, boolean enabled, Runnable action) {
    MaterialButton b =
        new MaterialButton(
            this,
            null,
            primary
                ? com.google.android.material.R.attr.materialButtonStyle
                : com.google.android.material.R.attr.materialButtonTonalStyle);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(15);
    b.setCornerRadius(dp(26));
    b.setMinHeight(dp(52));
    b.setInsetTop(dp(2));
    b.setInsetBottom(dp(2));
    b.setTextColor(primary ? getColor(R.color.on_accent) : ink);
    b.setBackgroundTintList(ColorStateList.valueOf(primary ? accent : soft));
    b.setEnabled(enabled);
    b.setAlpha(enabled ? 1f : 0.45f);
    b.setIconResource(actionIcon(label));
    b.setIconSize(dp(20));
    b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
    b.setIconTint(ColorStateList.valueOf(b.getCurrentTextColor()));
    b.setOnClickListener(v -> action.run());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(8);
    parent.addView(b, lp);
    return b;
  }

  MaterialButton textButton(LinearLayout parent, String label, Runnable action) {
    MaterialButton b =
        new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextColor(accent);
    b.setBackgroundTintList(ColorStateList.valueOf(android.graphics.Color.TRANSPARENT));
    b.setElevation(0);
    b.setMinHeight(dp(48));
    b.setIconResource(actionIcon(label));
    b.setIconSize(dp(20));
    b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
    b.setIconTint(ColorStateList.valueOf(b.getCurrentTextColor()));
    b.setOnClickListener(v -> action.run());
    parent.addView(b);
    return b;
  }

  int actionIcon(String label) {
    String key = label.toLowerCase(java.util.Locale.ROOT);
    if (key.contains("pause")) return R.drawable.ic_pause;
    if (key.contains("configure") || key.contains("allow automatic")) return R.drawable.ic_settings;
    if (key.contains("apply")) return R.drawable.ic_apply;
    if (key.contains("resume") || key.contains("retry") || key.contains("again"))
      return R.drawable.ic_refresh;
    if (key.contains("restore")) return R.drawable.ic_restore;
    if (key.contains("settings")) return R.drawable.ic_settings;
    if (key.contains("bluetooth") || key.contains("shizuku") || key.contains("connect"))
      return R.drawable.ic_bluetooth;
    return R.drawable.ic_info;
  }

  LinearLayout card() {
    return card(content);
  }

  LinearLayout card(LinearLayout parent) {
    MaterialCardView c = new MaterialCardView(this);
    c.setRadius(dp(28));
    c.setCardBackgroundColor(surface);
    c.setCardElevation(0);
    c.setStrokeWidth(0);
    LinearLayout inside = column();
    inside.setPadding(dp(20), dp(20), dp(20), dp(20));
    c.addView(inside);
    parent.addView(c, new LinearLayout.LayoutParams(-1, -2));
    return inside;
  }

  void render() {
    String next = deviceSelection.update(controller.snapshot, selected);
    if (!next.equals(selected)) {
      selected = next;
      controller.prefs.store.edit().putString("selected", selected).apply();
    }
    boolean ready =
        controller.authorized()
            && !controller.snapshot.has("error")
            && controller.snapshot.has("devices");
    String signature =
        controller.snapshot
            + controller.readiness
            + controller.results
            + controller.prefs.store.getAll()
            + controller.monitoring
            + selected
            + controller.isApplying(selected)
            + controller.reconnect.enabled()
            + controller.reconnect.status()
            + controller.reconnectBusy
            + controller.manualConnectionError
            + controller.reconnectActionMessage
            + controller.serviceRunning
            + controller.pendingOutput
            + controller.outputError;
    if (signature.equals(rendered)) return;
    rendered = signature;
    content.removeAllViews();
    LinearLayout header = row();
    ImageView logo = new ImageView(this);
    logo.setImageResource(R.drawable.ic_heart_bluetooth);
    logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    header.addView(logo, new LinearLayout.LayoutParams(dp(36), dp(36)));
    LinearLayout wordmark = column();
    wordmark.setPadding(dp(10), 0, 0, 0);
    text(wordmark, "BlueCodec", 28, ink, true);
    header.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
    content.addView(header);
    gap(content, 24);
    boolean bluetoothOff =
        controller.snapshot.has("bluetoothEnabled")
            && !controller.snapshot.optBoolean("bluetoothEnabled");
    if (bluetoothOff) {
      LinearLayout bluetoothBox = card();
      text(bluetoothBox, "Enable Bluetooth to configure", 20, ink, true);
      button(
          bluetoothBox,
          controller.reconnectBusy ? "Enabling…" : "Enable Bluetooth",
          true,
          controller.bluetoothServiceReady() && !controller.reconnectBusy,
          () -> {
            startMonitoring(false);
            controller.turnBluetoothOn(() -> {});
          });
      if (controller.reconnectActionFailed && !controller.reconnectActionMessage.isEmpty()) {
        gap(bluetoothBox, 8);
        text(
            bluetoothBox,
            controller.reconnectActionMessage,
            14,
            com.google.android.material.color.MaterialColors.getColor(
                bluetoothBox, androidx.appcompat.R.attr.colorError),
            true);
      }
      gap(content, 16);
    }
    JSONArray devices = controller.snapshot.optJSONArray("devices");
    if (!ready) {
      LinearLayout setup = card();
      text(setup, "Shizuku required", 21, ink, true);
      gap(setup, 8);
      text(
          setup,
          "Start Shizuku and allow BlueCodec access to change Bluetooth codecs.",
          15,
          muted,
          false);
      button(
          setup,
          controller.authorized() ? "Try again" : "Connect Shizuku",
          true,
          true,
          controller::requestAccess);
      textButton(
          setup,
          "Open Shizuku",
          () -> {
            Intent launch =
                getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
            if (launch != null) startActivity(launch);
            else
              new MaterialAlertDialogBuilder(this)
                  .setTitle("Shizuku is needed")
                  .setMessage("Install Shizuku and start its service, then return to BlueCodec.")
                  .setPositiveButton("OK", null)
                  .show();
          });
      textButton(setup, "Connection details", this::showDetails);
      gap(content, 16);
    }
    renderDevices(devices, ready);
    gap(content, 16);
    renderSoundOutput(devices, ready);
    gap(content, 18);
    LinearLayout reconnectBox = card();
    LinearLayout reconnectRow = row();
    LinearLayout reconnectText = column();
    text(reconnectText, "Disable automatic reconnect", 20, ink, true);
    gap(reconnectText, 6);
    text(reconnectText, "Experimental · Tested on Pixel 10 Pro", 13, accent, true);
    gap(reconnectText, 6);
    text(
        reconnectText,
        "Stop headsets reconnecting when you turn Bluetooth back on.",
        14,
        muted,
        false);
    reconnectRow.addView(reconnectText, new LinearLayout.LayoutParams(0, -2, 1));
    MaterialSwitch reconnectSwitch = new MaterialSwitch(this);
    reconnectSwitch.setContentDescription(
        "Disable automatic reconnect. Experimental. Tested on Pixel 10 Pro.");
    reconnectSwitch.setChecked(controller.preventReconnect());
    reconnectSwitch.setEnabled(!controller.reconnectBusy);
    reconnectSwitch.setOnCheckedChangeListener(
        (view, enabled) ->
            controller.setPreventReconnect(
                enabled,
                () -> {
                  if (controller.needsMonitoring()) startMonitoring(false);
                  else if (controller.serviceRunning)
                    stopService(new Intent(this, MonitorService.class));
                }));
    reconnectRow.addView(reconnectSwitch);
    reconnectBox.addView(reconnectRow);
    gap(reconnectBox, 16);
    View reconnectDivider = new View(this);
    reconnectDivider.setBackgroundColor(soft);
    reconnectBox.addView(reconnectDivider, new LinearLayout.LayoutParams(-1, dp(1)));
    gap(reconnectBox, 16);
    text(
        reconnectBox,
        "Only the BlueCodec tile applies this setting when turning Bluetooth off.",
        14,
        muted,
        false);
    textButton(reconnectBox, "Add Bluetooth tile", this::addBluetoothTile)
        .setIconResource(R.drawable.ic_add);
    if (!bluetoothOff
        && controller.reconnectActionFailed
        && !controller.reconnectActionMessage.isEmpty()) {
      gap(reconnectBox, 8);
      int errorColor =
          com.google.android.material.color.MaterialColors.getColor(
              reconnectBox, androidx.appcompat.R.attr.colorError);
      TextView failure =
          text(reconnectBox, controller.reconnectActionMessage, 14, errorColor, true);
      failure.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_warning, 0, 0, 0);
      failure.setCompoundDrawableTintList(ColorStateList.valueOf(errorColor));
      failure.setCompoundDrawablePadding(dp(8));
    }
    if (!controller.manualConnectionError.isEmpty()) {
      gap(reconnectBox, 8);
      text(
          reconnectBox,
          controller.manualConnectionError,
          14,
          com.google.android.material.color.MaterialColors.getColor(
              reconnectBox, androidx.appcompat.R.attr.colorError),
          true);
    }
    if (!controller.preventReconnect() && controller.reconnect.hasPendingRestore()) {
      MaterialButton restore =
          textButton(
              reconnectBox,
              "Restore automatic reconnect",
              () ->
                  controller.setReconnectEnabled(
                      false,
                      () -> {
                        if (!controller.needsMonitoring() && controller.serviceRunning)
                          stopService(new Intent(this, MonitorService.class));
                      }));
      restore.setEnabled(!controller.reconnectBusy && !bluetoothOff);
      if (!controller.reconnect.enabled()) {
        gap(reconnectBox, 8);
        text(reconnectBox, "Turn Bluetooth on to restore connection settings.", 14, muted, false);
      }
    }
    gap(content, 18);
    LinearLayout footer = row();
    textButton(footer, "How it works", this::showDetails);
    if (controller.monitoring) textButton(footer, "Pause codecs", controller::pauseCodecs);
    content.addView(footer);
  }

  void addBluetoothTile() {
    if (Build.VERSION.SDK_INT >= 33) {
      getSystemService(android.app.StatusBarManager.class)
          .requestAddTileService(
              new ComponentName(this, BluetoothTileService.class),
              "Bluetooth",
              android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_bluetooth),
              getMainExecutor(),
              result -> {
                if (result
                    == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                  Toast.makeText(this, "Bluetooth tile already added", Toast.LENGTH_SHORT).show();
              });
    } else {
      new MaterialAlertDialogBuilder(this)
          .setTitle("Add Bluetooth tile")
          .setMessage("Open Quick Settings, tap Edit, then add Bluetooth under BlueCodec.")
          .setPositiveButton("OK", null)
          .show();
    }
  }

  void renderDevice(LinearLayout parent, JSONObject device, boolean ready) {
    final String address = device.optString("address");
    final JSONObject active = device;
    boolean connected = device.optBoolean("connected");
    JSONObject current = device.optJSONObject("current");
    int preferred = controller.prefs.codec(address);
    JSONArray codecs = device.optJSONArray("selectable");
    LinearLayout deviceBox = card(parent);
    LinearLayout identity = row();
    ImageView icon = avatar(address, 48);
    icon.setImageDrawable(new DeviceAvatar(address, device.optBoolean("speaker")));
    identity.addView(icon);
    LinearLayout name = column();
    name.setPadding(dp(16), 0, 0, 0);
    text(name, device.optString("name", "Headphones"), 22, ink, true);

    gap(name, 4);
    text(
        name,
        connected && current != null
            ? "Connected · " + current.optString("name")
            : connected ? "Connected · Codec unavailable" : "Not connected",
        14,
        muted,
        false);
    identity.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    deviceBox.addView(identity);
    if (!connected) {
      button(
          deviceBox,
          controller.reconnectBusy ? "Connecting…" : "Connect",
          true,
          ready
              && !controller.reconnectBusy
              && controller.snapshot.optBoolean("bluetoothEnabled", true),
          () -> controller.connectDevice(address));
      return;
    }
    LinearLayout sound = deviceBox;
    gap(sound, 14);
    View codecDivider = new View(this);
    codecDivider.setBackgroundColor(soft);
    sound.addView(codecDivider, new LinearLayout.LayoutParams(-1, dp(1)));
    gap(sound, 8);
    if (connected && codecs != null && codecs.length() > 0) {
      ChipGroup group = new ChipGroup(this);
      group.setSingleSelection(true);
      group.setSelectionRequired(true);
      group.setChipSpacingHorizontal(dp(8));
      group.setChipSpacingVertical(dp(4));
      for (int i = 0; i < codecs.length(); i++) {
        JSONObject codec = codecs.optJSONObject(i);
        int type = codec.optInt("type");
        String label = codec.optString("name");
        Chip chip = new Chip(this);
        chip.setId(View.generateViewId());
        chip.setText(label);
        chip.setCheckable(true);
        chip.setChecked(
            type == (preferred >= 0 ? preferred : current == null ? -1 : current.optInt("type")));
        chip.setEnsureMinTouchTargetSize(true);
        chip.setChipCornerRadius(dp(18));
        chip.setMinHeight(dp(48));
        chip.setTextSize(14);
        chip.setChipStrokeWidth(0);
        chip.setChipBackgroundColor(
            new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {accent, soft}));
        chip.setTextColor(
            new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                new int[] {getColor(R.color.on_accent), ink}));
        chip.setCheckedIconTint(ColorStateList.valueOf(getColor(R.color.on_accent)));
        chip.setOnClickListener(
            v -> {
              int previous =
                  preferred >= 0 ? preferred : current == null ? -1 : current.optInt("type");
              if (type == previous) return;
              codecHelp.add(address);
              synchronized (controller.prefs) {
                controller.prefs.save(address, type, label);
              }
              controller.changed(address);
              rendered = "";
              render();
            });
        group.addView(chip);
      }
      sound.addView(group);
    } else
      text(
          sound,
          connected
              ? "Codecs unavailable. Try reconnecting."
              : "Connect this device to see supported codecs.",
          15,
          muted,
          false);
    if (preferred >= 0 && codecHelp.contains(address)) {
      gap(sound, 8);
      text(sound, CodecDescriptions.forName(controller.prefs.codecName(address)), 14, muted, false);
    }
    if (controller.isApplying(address)) {
      LinearLayout loading = row();
      loading.setPadding(0, dp(12), 0, dp(4));
      com.google.android.material.progressindicator.CircularProgressIndicator progress =
          new com.google.android.material.progressindicator.CircularProgressIndicator(this);
      progress.setIndeterminate(true);
      progress.setIndicatorSize(dp(22));
      progress.setTrackThickness(dp(2));
      progress.setIndicatorColor(accent);
      progress.setTrackColor(soft);
      loading.addView(progress, new LinearLayout.LayoutParams(dp(28), dp(28)));
      TextView label = text(loading, "Applying…", 15, muted, false);
      label.setPadding(dp(10), 0, 0, 0);
      label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
      sound.addView(loading);
    } else if (preferred >= 0 && (current == null || current.optInt("type", -1) != preferred))
      button(
          sound,
          "Apply " + controller.prefs.codecName(address),
          true,
          ready && connected,
          () -> controller.manual(address));
    if (codecs == null || codecs.length() <= 1) return;
    gap(deviceBox, 10);
    LinearLayout automatic = deviceBox;
    LinearLayout switchRow = row();
    LinearLayout description = column();
    text(description, "Apply on reconnect", 15, ink, false);
    switchRow.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
    MaterialSwitch toggle = new MaterialSwitch(this);
    toggle.setContentDescription("Automatically use the saved codec on reconnect");
    toggle.setChecked(controller.prefs.enabled(address));
    toggle.setEnabled(preferred >= 0);
    LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(-2, dp(56));
    switchLp.leftMargin = dp(12);
    switchRow.addView(toggle, switchLp);
    automatic.addView(switchRow);
    toggle.setOnCheckedChangeListener(
        (v, on) -> {
          synchronized (controller.prefs) {
            controller.prefs.enable(address, on);
          }
          controller.changed(address);
          if (on) startMonitoring();
          else if (!controller.needsMonitoring())
            stopService(new Intent(this, MonitorService.class));
          rendered = "";
          render();
        });
    gap(automatic, 12);
    String status =
        preferred < 0
            ? "Choose a codec first."
            : !controller.prefs.enabled(address)
                ? ""
                : !controller.monitoring
                    ? "Paused"
                    : !ready ? "Shizuku unavailable" : !connected ? "Waiting for connection" : "";
    String result = controller.results.get(address);
    if (result != null
        && (result.contains("unavailable")
            || result.contains("rejected")
            || result.contains("Exception")))
      status = "Could not apply the saved codec. Using Android’s selection.";
    if (!status.isEmpty()) text(automatic, status, 14, muted, false);
    if (controller.prefs.enabled(address) && !controller.monitoring)
      button(automatic, "Resume automatic switching", true, true, this::startMonitoring);
  }

  void renderDevices(JSONArray devices, boolean ready) {
    java.util.List<JSONObject> connected = new java.util.ArrayList<>();
    if (devices != null)
      for (int i = 0; i < devices.length(); i++) {
        JSONObject d = devices.optJSONObject(i);
        if (d != null && d.optBoolean("connected")) connected.add(d);
      }
    if (connected.isEmpty()) {
      try {
        JSONObject last = new JSONObject(controller.prefs.store.getString("lastConnected", ""));
        last.put("connected", false);
        JSONObject latest = CodecController.find(controller.snapshot, last.optString("address"));
        if (latest != null) last = latest;
        renderDevice(content, last, ready);
      } catch (JSONException e) {
        LinearLayout empty = card();
        text(empty, "No connected audio devices", 20, ink, true);
        button(
            empty,
            "Open Bluetooth settings",
            true,
            true,
            () -> startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
      }
      return;
    }
    if (connected.size() == 1) {
      renderDevice(content, connected.get(0), ready);
      return;
    }
    DevicePager pager = new DevicePager(this);
    pager.setId(View.generateViewId());
    pager.setAdapter(
        new androidx.viewpager.widget.PagerAdapter() {
          public int getCount() {
            return connected.size();
          }

          public boolean isViewFromObject(View view, Object object) {
            return view == object;
          }

          public Object instantiateItem(ViewGroup container, int position) {
            LinearLayout page = column();
            page.setTag(position);
            renderDevice(page, connected.get(position), ready);
            container.addView(page);
            return page;
          }

          public void destroyItem(ViewGroup container, int position, Object object) {
            container.removeView((View) object);
          }
        });
    content.addView(pager, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout dots = row();
    dots.setGravity(Gravity.CENTER);
    for (int i = 0; i < connected.size(); i++) {
      final int index = i;
      android.widget.ImageButton dot = new android.widget.ImageButton(this);
      dot.setBackgroundColor(android.graphics.Color.TRANSPARENT);
      // Keep 48dp touch targets while drawing the two indicators toward each other.
      if (connected.size() == 2) dot.setPadding(i == 0 ? dp(20) : 0, 0, i == 1 ? dp(20) : 0, 0);
      dot.setContentDescription(
          "Configure "
              + connected.get(i).optString("name")
              + ", "
              + (i + 1)
              + " of "
              + connected.size());
      dot.setOnClickListener(v -> pager.setCurrentItem(index, true));
      dots.addView(dot, new LinearLayout.LayoutParams(dp(48), dp(48)));
    }
    content.addView(dots);
    Runnable updateDots =
        () -> {
          for (int i = 0; i < dots.getChildCount(); i++) {
            android.graphics.drawable.GradientDrawable shape =
                new android.graphics.drawable.GradientDrawable();
            shape.setColor(i == pager.getCurrentItem() ? accent : muted);
            shape.setCornerRadius(dp(6));
            shape.setSize(dp(i == pager.getCurrentItem() ? 22 : 8), dp(8));
            android.widget.ImageButton dot = (android.widget.ImageButton) dots.getChildAt(i);
            dot.setImageDrawable(shape);
            dot.setSelected(i == pager.getCurrentItem());
          }
          pager.requestLayout();
        };
    pager.addOnPageChangeListener(
        new androidx.viewpager.widget.ViewPager.SimpleOnPageChangeListener() {
          public void onPageSelected(int position) {
            selected = connected.get(position).optString("address");
            controller.prefs.store.edit().putString("selected", selected).apply();
            updateDots.run();
          }
        });
    for (int i = 0; i < connected.size(); i++)
      if (selected.equals(connected.get(i).optString("address"))) pager.setCurrentItem(i, false);
    updateDots.run();
    pager.post(pager::requestLayout);
  }

  void renderSoundOutput(JSONArray devices, boolean ready) {
    LinearLayout output = card();
    TextView outputTitle = text(output, "Sound output", 20, ink, true);
    outputTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(
        R.drawable.ic_sound_output, 0, 0, 0);
    outputTitle.setCompoundDrawableTintList(ColorStateList.valueOf(ink));
    outputTitle.setCompoundDrawablePadding(dp(10));
    JSONObject routing = controller.snapshot.optJSONObject("soundOutput");
    String actual = routing == null ? "" : routing.optString("selected");
    RadioGroup group = new RadioGroup(this);
    output.addView(group);
    addOutput(group, "This phone", "phone", actual, ready && routing != null);
    if (devices != null)
      for (int i = 0; i < devices.length(); i++) {
        JSONObject device = devices.optJSONObject(i);
        if (device != null && device.optBoolean("connected"))
          addOutput(
              group,
              device.optString("name"),
              device.optString("address"),
              actual,
              ready && routing != null);
      }
    String error =
        !controller.outputError.isEmpty()
            ? controller.outputError
            : routing == null
                ? "Connect Shizuku to choose sound output."
                : routing.optString("error");
    if (!error.isEmpty()) text(output, error, 14, muted, false);
    else if (actual.isEmpty()) text(output, "Current output unavailable", 14, muted, false);
  }

  void addOutput(RadioGroup group, String name, String target, String actual, boolean ready) {
    com.google.android.material.radiobutton.MaterialRadioButton radio =
        new com.google.android.material.radiobutton.MaterialRadioButton(this);
    radio.setId(View.generateViewId());
    radio.setText(name);
    radio.setContentDescription(
        name + (target.equals(controller.pendingOutput) ? ", switching" : ""));
    radio.setTextColor(ink);
    radio.setTextSize(16);
    radio.setMinHeight(dp(48));
    radio.setButtonTintList(
        new ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
            new int[] {accent, muted}));
    radio.setEnabled(ready && controller.pendingOutput.isEmpty());
    LinearLayout outputRow = row();
    outputRow.addView(radio, new LinearLayout.LayoutParams(0, -2, 1));
    if (target.equals(controller.pendingOutput)) {
      com.google.android.material.progressindicator.CircularProgressIndicator spinner =
          new com.google.android.material.progressindicator.CircularProgressIndicator(this);
      spinner.setIndeterminate(true);
      spinner.setIndicatorSize(dp(20));
      spinner.setTrackThickness(dp(2));
      spinner.setIndicatorColor(accent);
      spinner.setTrackColor(soft);
      spinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      outputRow.addView(spinner, new LinearLayout.LayoutParams(dp(28), dp(28)));
    }
    group.addView(outputRow, new RadioGroup.LayoutParams(-1, -2));
    radio.setChecked(target.equals(actual));
    radio.setOnClickListener(
        v -> {
          radio.setChecked(target.equals(actual));
          if (!target.equals(actual)) controller.selectSoundOutput(target);
        });
    radio.setTag(target);
  }

  void showDetails() {
    BottomSheetDialog dialog = new BottomSheetDialog(this);
    LinearLayout detail = column();
    detail.setPadding(dp(28), dp(28), dp(28), dp(32));
    text(detail, "About BlueCodec", 23, ink, true);
    gap(detail, 12);
    text(
        detail,
        "Saves a codec per device and applies it when the device reconnects.",
        16,
        muted,
        false);
    gap(detail, 18);
    text(detail, "Background operation", 18, ink, true);
    gap(detail, 6);
    text(
        detail,
        "A notification keeps automatic switching active in the background. Unsupported codecs fall"
            + " back to Android’s selection.",
        15,
        muted,
        false);
    gap(detail, 18);
    text(detail, "After restarting your phone", 18, ink, true);
    gap(detail, 6);
    text(
        detail,
        "Start Shizuku again, return here and resume automatic switching. If Shizuku has just"
            + " restarted, open its app and then return to BlueCodec.",
        15,
        muted,
        false);
    gap(detail, 18);
    text(detail, "Manual reconnect", 18, ink, true);
    gap(detail, 6);
    text(
        detail,
        "Enable Disable automatic reconnect and use the BlueCodec tile to switch Bluetooth off."
            + " This blocks headset reconnections first. Android’s normal"
            + " Bluetooth toggle does not do this. Turn Bluetooth on normally, then connect from"
            + " Android Settings or BlueCodec. Applies to classic Bluetooth audio, not LE Audio."
            + " Use Restore automatic reconnect before uninstalling BlueCodec.",
        15,
        muted,
        false);
    gap(detail, 18);
    text(detail, "Connection status", 18, ink, true);
    gap(detail, 6);
    TextView diagnostics =
        text(
            detail,
            controller.readiness
                + "\n\n"
                + controller.results.getOrDefault(selected, "No recent changes."),
            13,
            muted,
            false);
    diagnostics.append("\n\n" + controller.reconnect.status());
    if (controller.snapshot.has("volumeSync"))
      diagnostics.append("\n\n" + controller.snapshot.optString("volumeSync"));
    diagnostics.setTextIsSelectable(true);
    gap(detail, 16);
    text(
        detail,
        "Applies to every app using this device. Android chooses the sample rate and bit depth.",
        13,
        muted,
        false);
    textButton(
        detail,
        "Bluetooth settings",
        () -> startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(detail);
    dialog.setContentView(scroll);
    dialog.show();
  }

  void startMonitoring() {
    startMonitoring(true);
  }

  void startMonitoring(boolean resumeCodecs) {
    resumeAfterPermission = resumeCodecs;
    if (resumeCodecs) controller.prefs.store.edit().putBoolean("codecPaused", false).apply();
    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, 10);
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 11);
      return;
    }
    beginMonitoring();
  }

  void beginMonitoring() {
    try {
      if (controller.needsMonitoring())
        startForegroundService(new Intent(this, MonitorService.class));
    } catch (Exception e) {
      controller.readiness = "Could not start automatic switching: " + CodecService.error(e);
      controller.publish();
    }
  }

  public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(code, permissions, grants);
    if (code == 10) {
      if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
        startMonitoring(resumeAfterPermission);
      else
        new MaterialAlertDialogBuilder(this)
            .setTitle("Nearby devices access needed")
            .setMessage(
                "Allow Nearby devices in Android app settings to use automatic switching. Your"
                    + " choice is still saved.")
            .setPositiveButton(
                "Open app settings",
                (d, w) ->
                    startActivity(
                        new Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:" + getPackageName()))))
            .setNegativeButton("Later", null)
            .show();
    }
    if (code == 11) {
      beginMonitoring();
      if (grants.length == 0 || grants[0] != PackageManager.PERMISSION_GRANTED)
        new MaterialAlertDialogBuilder(this)
            .setMessage(
                "Switching can run, but its notification and restart reminders will be hidden. You"
                    + " can enable notifications in Android settings.")
            .setPositiveButton("OK", null)
            .show();
    }
  }

  protected void onResume() {
    super.onResume();
    deviceSelection.opened();
    controller.listeners.add(update);
    handler.post(poll);
    render();
  }

  protected void onPause() {
    handler.removeCallbacks(poll);
    controller.listeners.remove(update);
    super.onPause();
  }
}
