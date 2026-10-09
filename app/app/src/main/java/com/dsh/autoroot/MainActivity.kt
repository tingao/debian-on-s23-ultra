package com.dsh.autoroot

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var overall: TextView
    private lateinit var components: LinearLayout
    private lateinit var sshText: TextView
    private lateinit var consoleOut: TextView
    private lateinit var cmdInput: EditText
    private lateinit var autoSwitch: Switch
    private lateinit var pauseBox: CheckBox
    private lateinit var setupBox: LinearLayout
    private lateinit var setupButtonsBox: LinearLayout
    private lateinit var pairCode: EditText
    private lateinit var heroSub: TextView
    private var setupBusy = false
    private var busy = false

    /** Same file RootService checks, in the app's private storage. */
    private fun pauseFile() = java.io.File(filesDir, "autoroot.pause")

    private val GREEN = Color.parseColor("#4CAF50")
    private val AMBER = Color.parseColor("#FFB300")
    private val RED = Color.parseColor("#F44336")
    private val DIM = Color.parseColor("#9E9E9E")

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, grant ->
        runConsole("shizuku permission result: grant=$grant")
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sh.init(this)
        Shizuku.addRequestPermissionResultListener(permListener)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    private fun buildUi() {
        // The pairing strip is deliberately OUTSIDE the scroll view. It has to stay on
        // screen no matter how far down the page you are, because the code it wants is
        // only valid while another app's screen is displaying it.
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg(this@MainActivity))
        }
        val pairBar = Ui.panel(this, padDp = 10).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(14); rightMargin = dp(14)
                topMargin = dp(14); bottomMargin = dp(0)
            }
        }
        pairBar.addView(Ui.heading(this, "Pairing code"))
        val pairRow = Ui.row(this)
        pairCode = Ui.input(this, "6 digits", numeric = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { rightMargin = dp(8); bottomMargin = 0 }
        }
        pairRow.addView(pairCode)
        pairRow.addView(Ui.button(this, "Pair", primary = true).apply {
            setOnClickListener { pairAndStart() }
        })
        pairBar.addView(pairRow)
        outer.addView(pairBar)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(24))
        }

        // Hero: the one thing worth reading at a glance.
        val hero = Ui.panel(this)
        overall = Ui.label(this, "", 21f).apply { setTypeface(typeface, Typeface.BOLD) }
        hero.addView(overall)
        heroSub = Ui.label(this, Setup.deviceSummary(), 11f, Ui.dim(this))
        hero.addView(heroSub)
        root.addView(hero)

        // First card on the screen, because on a fresh install it is the only one
        // that matters: what is still missing, and a button per step.
        val setup = Ui.panel(this)
        setup.addView(Ui.heading(this, "Set up this phone"))
        setupBox = Ui.column(this)
        setup.addView(setupBox)
        // Filled in by renderSetup, which already has the step list. Building the
        // buttons separately would mean running the checks twice, and the checks do
        // shell calls.
        setupButtonsBox = Ui.column(this)
        setup.addView(setupButtonsBox)
        setup.addView(shizukuPairRow())
        root.addView(setup)

        val comps = Ui.panel(this)
        comps.addView(Ui.heading(this, "Components"))
        components = Ui.column(this)
        comps.addView(components)
        comps.addView(buttonRow())
        root.addView(comps)

        val auto = Ui.panel(this)
        auto.addView(Ui.heading(this, "Auto-start after reboot"))
        // The safety catch. After a reboot the app waits AUTO_SETTLE_SECONDS before
        // touching anything, which is the window to turn this off (or to kill the
        // app). If autostart is off, a reboot leaves the phone completely alone -
        // no exploit, no Debian, nothing that could loop.
        autoSwitch = Switch(this).apply {
            text = "Root + start Debian automatically"
            textSize = 13f
            setTextColor(Ui.text(this@MainActivity))
            isChecked = RootService.autoStartEnabled(this@MainActivity)
            setPadding(0, dp(2), 0, dp(6))
            setOnCheckedChangeListener { _, on ->
                RootService.enableAutoStart(this@MainActivity, on)
                toast(if (on) "autostart ENABLED" else "autostart DISABLED - a reboot will do nothing")
                Sh.log("ui: autostart set to $on")
            }
        }
        auto.addView(autoSwitch)

        // Second, independent override: works even if the UI cannot be opened.
        pauseBox = CheckBox(this).apply {
            text = "Pause everything now"
            textSize = 13f
            setTextColor(Ui.text(this@MainActivity))
            isChecked = pauseFile().exists()
            setOnCheckedChangeListener { _, on ->
                if (on) {
                    pauseFile().writeText("paused")
                    toast("paused - kill the app to stop a running flow")
                } else {
                    pauseFile().delete()
                    toast("resumed")
                }
                Sh.log("ui: pause file set to $on")
            }
        }
        auto.addView(pauseBox)
        auto.addView(Ui.label(this, "Creates files/autoroot.pause", 10f, Ui.dim(this)))
        root.addView(auto)

        val ssh = Ui.panel(this)
        ssh.addView(Ui.heading(this, "SSH access"))
        sshText = Ui.mono(this, 11f).apply { setPadding(0, 0, 0, dp(6)) }
        ssh.addView(sshText)
        val copy = Ui.button(this, "Copy SSH command")
        copy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ssh", sshText.text))
            toast("copied")
        }
        ssh.addView(copy)
        root.addView(ssh)

        val con = Ui.panel(this)
        con.addView(Ui.heading(this, "Debian console"))
        cmdInput = EditText(this).apply {
            hint = "command to run inside Debian"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            textSize = 13f
            setTextColor(Ui.text(this@MainActivity))
            setHintTextColor(Ui.dim(this@MainActivity))
        }
        con.addView(cmdInput)
        val runCmd = Ui.button(this, "Run in Debian", primary = true)
        runCmd.setOnClickListener {
            val c = cmdInput.text.toString().trim()
            if (c.isNotEmpty()) {
                cmdInput.setText("")
                runConsole("$ $c")
                Thread {
                    val r = Sh.runInDebian(c)
                    ui.post { runConsole(r.ifBlank { "(no output)" }) }
                }.start()
            }
        }
        val runRow = Ui.buttonRow(this)
        Ui.stretch(runCmd, 1f, this)
        runRow.addView(runCmd)
        con.addView(runRow)

        consoleOut = Ui.mono(this, 11f).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Ui.sunken(this@MainActivity))
        }
        con.addView(consoleOut)

        // The log has grown to 1500 characters of scrollback with no way to clear it, and
        // Sh.clearLog() existed but was never called from anywhere.
        val clearRow = Ui.buttonRow(this)
        val clear = Ui.button(this, "Clear log").apply {
            setOnClickListener {
                Sh.clearLog()
                consoleOut.text = ""
                toast("log cleared")
            }
        }
        Ui.stretch(clear, 1f, this)
        clearRow.addView(clear)
        con.addView(clearRow)
        root.addView(con)

        val sv = ScrollView(this).apply { addView(root) }
        outer.addView(sv)

        // targetSdk 35 means Android 16 forces edge-to-edge, and this app paints its own
        // background, so it has to keep its content clear of the system bars itself.
        // Without this the primary button renders 78px tall instead of 135px with its
        // bottom inside the back-gesture strip, and the pairing heading sits under the
        // punch-hole cutout.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(outer) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        setContentView(outer)

        // The STATUS mirror lines are written for the file, not for reading. Including
        // them meant the console opened mid-token: the live dump literally began with
        // "g (pid 10781) | Wi-Fi address=…". Drop them from the console's first fill.
        runConsole(
            Sh.readLog().takeLast(1500)
                .lines()
                .filterNot { it.contains("STATUS ") }
                .joinToString("\n")
        )

        // While this install still needs the pairing code, keep the code box in the
        // shade. It is the only input that can float over the Settings screen showing
        // the code, so leaving it there means it is always within reach rather than
        // having to be summoned each time.
        if (!ShizukuBootstrap.hasKey(this) && !ShizukuBootstrap.serverRunning()) {
            try {
                ContextCompat.startForegroundService(this, PairingService.startIntent(this))
            } catch (t: Throwable) {
                // Never swallow this one: if the service cannot start there is no code
                // box anywhere, and the user would be left staring at a button that
                // silently does nothing.
                runConsole("could not start the pairing notification: ${t.message}")
                Sh.log("pairing: startForegroundService failed: $t")
            }
        }
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(DIM)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun buttonRow(): LinearLayout {
        val row = Ui.buttonRow(this)
        fun mk(label: String, weight: Float, on: () -> Unit) =
            Ui.button(this, label).apply { setOnClickListener { on() } }
                .also { Ui.stretch(it, weight, this) }

        row.addView(mk("Refresh", 1f) { refresh() })
        row.addView(mk("Run now", 1.2f) {
            ContextCompat.startForegroundService(
                this, Intent(this, RootService::class.java).setAction(RootService.ACTION_RUN)
            )
            runConsole("root flow started - see COMPONENTS for progress")
            ui.postDelayed({ refresh() }, 3000)
        })
        row.addView(mk("Grant Shizuku", 1.3f) {
            try {
                if (!Sh.shizukuReady()) runConsole("Shizuku is not running yet")
                else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) runConsole("already granted")
                else Shizuku.requestPermission(0)
            } catch (t: Throwable) { runConsole("requestPermission failed: ${t.message}") }
        })
        return row
    }

    /**
     * The three setup actions.
     *
     * Stacked rather than a single row of three: at a third of the width the labels
     * either wrapped onto two lines or were squeezed, and the row read as ragged.
     * Two short ones share a row and the primary action gets its own.
     */
    /**
     * The setup actions, shown only where they would do something.
     *
     * These used to be fixed: "Install Shizuku" stayed on screen on a phone that already
     * had it, and "Get Debian" stayed on screen with the rootfs already unpacked, so two
     * buttons that looked live did nothing when pressed. Driving them off the same step
     * list the checklist uses means the button is there exactly when it applies.
     */
    private fun renderSetupButtons(steps: List<Setup.Step>) {
        setupButtonsBox.removeAllViews()
        val state = steps.associate { it.title to it.state }
        fun ok(t: String) = state[t] == Setup.State.OK
        val needsInstall = state["Shizuku installed"] != null && !ok("Shizuku installed")
        val needsRootfs = state["Debian rootfs"] != null && !ok("Debian rootfs")

        if (needsInstall || needsRootfs) {
            val row = Ui.buttonRow(this)
            if (needsInstall) {
                val b = Ui.button(this, "Install Shizuku").apply {
                    setOnClickListener {
                        try {
                            Setup.installShizuku(this@MainActivity)
                            runConsole("opened the installer for the bundled Shizuku APK - accept the prompt")
                        } catch (t: Throwable) {
                            runConsole("could not open the installer: ${t.message}")
                        }
                    }
                }
                Ui.stretch(b, 1f, this)
                row.addView(b)
            }
            if (needsRootfs) {
                val b = Ui.button(this, "Get Debian").apply {
                    setOnClickListener { downloadRootfsUi() }
                }
                Ui.stretch(b, 1f, this)
                row.addView(b)
            }
            setupButtonsBox.addView(row)
        }

        setupButtonsBox.addView(Ui.wideButton(this, "Set up everything", primary = true).apply {
            setOnClickListener { runSetup() }
        })
    }

    /**
     * Pair with the phone's own adb, so Shizuku can be started without a computer.
     *
     * This is the alternative to plugging into a PC, and it is needed once in the life
     * of the install, so it sits below the main actions and reads as steps rather than
     * as a paragraph.
     */
    private fun shizukuPairRow(): LinearLayout {
        val box = Ui.column(this).apply { setPadding(0, dp(14), 0, 0) }

        val divider = View(this).apply {
            setBackgroundColor(Ui.line(this@MainActivity))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { bottomMargin = dp(12) }
        }
        box.addView(divider)

        box.addView(Ui.label(this, "Start Shizuku with no computer", 13f).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(Ui.label(this, "Needed once, ever.", 11f, Ui.dim(this)).apply {
            setPadding(0, dp(2), 0, dp(8))
        })

        // One button, because the two halves have to happen together. The code is
        // displayed by Settings, in another app, so anything drawn here ends up behind
        // it, which is why the input is a notification instead. Tapping this starts the
        // notification and opens Settings in the same gesture, so the reply box is
        // already waiting in the shade by the time the code appears on screen.
        box.addView(Ui.label(this,
            "1.  Tap \"Open Wireless debugging\" below. It opens Settings already " +
            "scrolled to the setting, and posts the code box to your notification " +
            "shade at the same time\n" +
            "2.  Turn Wireless debugging on, then tap \"Pair device with pairing code\"\n" +
            "3.  Pull down the shade and type the code straight into the notification",
            12f).apply { setPadding(0, dp(2), 0, dp(8)) })

        box.addView(Ui.wideButton(this, "Open Wireless debugging", primary = false).apply {
            setOnClickListener {
                try {
                    ContextCompat.startForegroundService(
                        this@MainActivity, PairingService.startIntent(this@MainActivity)
                    )
                    runConsole("pairing started - the code box is in your notification shade")
                } catch (t: Throwable) {
                    runConsole("could not start pairing: ${t.message}")
                }
                try {
                    startActivity(ShizukuBootstrap.settingsIntent(this@MainActivity))
                } catch (t: Throwable) {
                    runConsole("could not open settings: ${t.message}")
                }
            }
        })

        // The strip at the top is NOT a usable fallback: opening this settings screen
        // uses NEW_TASK|CLEAR_TASK, so MainActivity is off screen while the code is
        // displayed. Saying otherwise sent the user looking for a control that was not
        // there. What they actually need to know is that the setting has to stay on
        // until the server is up.
        box.addView(Ui.label(this,
            "Leave Wireless debugging on until the Debian server row turns green. " +
            "If you turn it off in between, this step has to be done again.",
            11f, Ui.dim(this)).apply {
            setPadding(0, dp(8), 0, 0)
        })
        return box
    }

    /**
     * Pair once, then bring Shizuku up over that pairing.
     *
     * The code is needed exactly once in the life of the install: pairing swaps keys,
     * our key is kept in this app's private storage, and the phone remembers it. Every
     * later start goes through startShizukuNoCode() instead, which is also what the
     * boot path uses. If this ever asks again, the app's data was cleared or the phone
     * forgot the key.
     */
    private fun pairAndStart() {
        if (setupBusy) { toast("already working"); return }
        val code = pairCode.text.toString().trim()
        if (code.length < 4) { toast("type the pairing code"); return }
        setupBusy = true
        runConsole("=== one-time pairing so this is never asked for again ===")
        Thread {
            val paired = try {
                ShizukuBootstrap.pair(this, code) { line -> ui.post { runConsole(line) } }
            } catch (t: Throwable) {
                ui.post { runConsole("pairing error: ${t.message}") }
                false
            }
            var started = false
            if (paired) {
                started = try {
                    ShizukuBootstrap.startServer(this) { line -> ui.post { runConsole(line) } }
                } catch (t: Throwable) {
                    ui.post { runConsole("start error: ${t.message}") }
                    false
                }
            }
            ui.post {
                setupBusy = false
                toast(
                    when {
                        started -> "Shizuku is up, and paired for good"
                        paired -> "paired; tap Set up everything to start Shizuku"
                        else -> "pairing did not finish"
                    }
                )
                refresh()
            }
        }.start()
    }

    /** The ordinary path: no code, using the key we already paired. */
    private fun startShizukuNoCode() {
        if (setupBusy) { toast("already working"); return }
        setupBusy = true
        runConsole("=== starting Shizuku with the stored key ===")
        Thread {
            val ok = try {
                ShizukuBootstrap.startServer(this) { line -> ui.post { runConsole(line) } }
            } catch (t: Throwable) {
                ui.post { runConsole("start error: ${t.message}") }
                false
            }
            ui.post {
                setupBusy = false
                toast(if (ok) "Shizuku is up" else "could not start Shizuku")
                refresh()
            }
        }.start()
    }

    private fun renderSetup(steps: List<Setup.Step>) {
        setupBox.removeAllViews()
        for (s in steps) {
            val color = when (s.state) {
                Setup.State.OK -> Ui.ok()
                Setup.State.NEEDED -> Ui.warn()
                Setup.State.BLOCKED -> Ui.bad()
                Setup.State.UNKNOWN -> Ui.dim(this)
            }
            // A cross means "broken". Something merely not done yet is not broken, and a
            // healthy phone that has not been set up should not read as faulty.
            val glyph = when (s.state) {
                Setup.State.OK -> "\u2713"
                Setup.State.BLOCKED -> "\u2715"
                Setup.State.NEEDED -> "!"
                Setup.State.UNKNOWN -> "\u2022"
            }
            val r = Ui.row(this)
            r.addView(Ui.statusMark(this, color, glyph))
            val col = Ui.column(this)
            col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            col.addView(Ui.label(this, s.title, 13f).apply { setTypeface(typeface, Typeface.BOLD) })
            val body = s.detail + (s.manual?.let { "\n" + it } ?: "")
            if (body.isNotBlank()) {
                col.addView(Ui.label(this, body, 11f, Ui.dim(this)).apply { setTextIsSelectable(true) })
            }
            r.addView(col)
            setupBox.addView(r)
        }
        // Rebuild the actions from the same list, so a button is only offered when it
        // would actually do something.
        renderSetupButtons(steps)
    }

    private fun downloadRootfsUi() {
        if (setupBusy) { toast("already working"); return }
        setupBusy = true
        runConsole("downloading the Debian 13 arm64 rootfs, about 86 MB")
        Thread {
            val err = try {
                Setup.downloadRootfs(this) { pct ->
                    if (pct % 10 == 0) ui.post { runConsole("  $pct%") }
                }
            } catch (t: Throwable) { t.message ?: "?" }
            ui.post {
                setupBusy = false
                if (err == null) {
                    runConsole("rootfs downloaded. Tap 'Set up everything' to unpack it.")
                    toast("downloaded")
                } else {
                    runConsole("download failed: $err")
                    toast("download failed")
                }
                refresh()
            }
        }.start()
    }

    /**
     * Walk the steps in order and do whatever can be automated. Anything that
     * cannot be done from inside the app (installing an app, starting Shizuku the
     * first time, granting the permission) is reported with the exact command
     * instead of being silently skipped.
     */
    private fun runSetup() {
        if (setupBusy) { toast("already working"); return }
        setupBusy = true
        runConsole("=== setup ===")
        Thread {
            try {
                val by = Setup.steps(this).associateBy { it.title }
                fun ok(t: String) = by[t]?.state == Setup.State.OK

                if (!ok("Shizuku installed")) {
                    ui.post {
                        runConsole("Shizuku is not installed. Opening the bundled installer - accept the prompt, then tap Set up again.")
                        try { Setup.installShizuku(this) } catch (t: Throwable) { runConsole("installer failed: ${t.message}") }
                    }
                    return@Thread
                }
                if (!ok("Shizuku running")) {
                    // If we have paired before, this needs nothing from the user: the
                    // stored key plus mDNS is enough. Only a first-ever run needs the
                    // code, and that is the pairing row's job.
                    val started = try {
                        ShizukuBootstrap.startServer(this) { line -> ui.post { runConsole(line) } }
                    } catch (t: Throwable) {
                        ui.post { runConsole("could not start Shizuku: ${t.message}") }
                        false
                    }
                    if (!started) {
                        ui.post {
                            runConsole("Shizuku is not running. If this is the first time, use the")
                            runConsole("pairing row above: Developer options > Wireless debugging >")
                            runConsole("on, tap \"Pair device with pairing code\", and type that code in.")
                            runConsole("After that this starts on its own and never asks again.")
                        }
                        return@Thread
                    }
                }
                if (!ok("Shizuku permission for this app")) {
                    ui.post {
                        runConsole("asking Shizuku for permission - accept the prompt")
                        try { Shizuku.requestPermission(0) } catch (t: Throwable) { runConsole("request failed: ${t.message}") }
                    }
                    return@Thread
                }
                if (!ok("root")) {
                    ui.post { runConsole("no root yet - running the exploit and late-loading KernelSU") }
                    RootFlow.run(this)
                    if (!Setup.steps(this).any { it.title == "root" && it.state == Setup.State.OK }) {
                        ui.post { runConsole("still not rooted. Read /data/local/tmp/bope.log; if the phone is warm the exploit is waiting for the thermal gate.") }
                    }
                }
                if (!ok("Debian rootfs")) {
                    val f = Setup.rootfsFile(this)
                    if (!f.exists()) {
                        ui.post { runConsole("downloading the Debian rootfs, about 86 MB") }
                        val err = Setup.downloadRootfs(this) { pct -> if (pct % 10 == 0) ui.post { runConsole("  $pct%") } }
                        if (err != null) {
                            ui.post { runConsole("download failed: $err") }
                            return@Thread
                        }
                    }
                    ui.post { runConsole("unpacking the rootfs and starting sshd, this takes a few minutes") }
                    val r = Setup.bootstrap(this)
                    ui.post { runConsole("bootstrap: $r") }
                }
                ui.post { runConsole("starting the server") }
                Sh.exec("su -c 'sh /data/local/tmp/debian-start.sh'")
                ui.post { runConsole("setup finished. Check the components below.") }
            } catch (t: Throwable) {
                ui.post { runConsole("setup error: ${t.message}") }
            } finally {
                ui.post { setupBusy = false; refresh() }
            }
        }.start()
    }

    private fun refresh() {
        if (busy) return
        busy = true
        Thread {
            val items = try { Status.snapshot() } catch (t: Throwable) {
                listOf(Status.Item("Error", t.message ?: "?", -1))
            }
            val steps = try { Setup.steps(this) } catch (t: Throwable) {
                listOf(Setup.Step("setup check failed", Setup.State.BLOCKED, t.message ?: "?"))
            }
            val ip = try { Status.wifiIp() } catch (t: Throwable) { "" }
            val logTail = Sh.readLog().takeLast(700)
            ui.post {
                renderSetup(steps)
                components.removeAllViews()
                var bad = 0; var warn = 0
                for (it in items) {
                    if (it.state == -1) bad++ else if (it.state == 0) warn++
                    components.addView(row(it))
                }
                overall.text = when {
                    bad > 0 -> "$bad component(s) need attention"
                    warn > 0 -> "working, $warn warning(s)"
                    else -> "all systems nominal"
                }
                overall.setTextColor(when {
                    bad > 0 -> RED
                    warn > 0 -> AMBER
                    else -> GREEN
                })
                sshText.text = if (ip.isEmpty()) "no Wi-Fi address" else
                    "ssh -p 1304 root@$ip\n" +
                    "ssh-keygen -R '[$ip]:1304'   # if the host key changed\n\n" +
                    "key  : <repo>\\tools\\debian_key\n" +
                    "pass : root  (change it: run passwd)\n\n" +
                    "Port 1304 listens on the LAN too. To reach it from outside,\n" +
                    "forward TCP 1304 on your router, or put a tunnel in front of it."
                // Mirror the snapshot into the log so it can be checked without
                // looking at the screen.
                Sh.log("STATUS " + items.joinToString(" | ") { "${it.label}=${it.value}" })
                busy = false
            }
        }.start()
    }

    private fun row(it: Status.Item): LinearLayout {
        val color = when (it.state) { 1 -> Ui.ok(); 0 -> Ui.warn(); else -> Ui.bad() }
        // Cross is reserved for broken; a warning is not broken.
        val glyph = when (it.state) { 1 -> "\u2713"; 0 -> "!"; else -> "\u2715" }
        val r = Ui.row(this)
        r.addView(Ui.statusMark(this, color, glyph))
        // Label, then a spacer that absorbs the slack, then the value hard against the
        // right edge. Giving the *label* the weight instead pushed the value onto its
        // own line as soon as the text was long, which is what the review measured as
        // some rows wrapping where their neighbours stayed one line.
        r.addView(Ui.label(this, it.label, 12f).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        r.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        r.addView(Ui.mono(this, 12f, Ui.dim(this)).apply {
            text = it.value
            gravity = android.view.Gravity.END
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })
        return r
    }

    /**
     * Append to the on-screen console, and to the log file.
     *
     * Screen only was a mistake: the setup steps report through here, so a run
     * that failed halfway left its explanation in a view that scrolls past and is
     * gone the moment the dashboard refreshes. The log is what survives.
     */
    private fun runConsole(s: String) {
        consoleOut.append("\n" + s.trim())
        Sh.log(s.trim())
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
