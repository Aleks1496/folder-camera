using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

internal static class WindowsReceiver
{
    internal const string Title = "Приёмник Folder Camera";
    internal const string StartupName = "FolderCameraReceiver";
    internal const string StartupKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    [STAThread]
    private static void Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        try
        {
            var options = new Dictionary<string, string>();
            bool background = false;
            for (int i = 0; i < args.Length; i++)
            {
                if (args[i] == "--background") { background = true; continue; }
                string key = args[i];
                if (key != "--state" && key != "--root" && key != "--bind" && key != "--port")
                    throw new ArgumentException("Недопустимый параметр запуска.");
                if (++i == args.Length || args[i].Length == 0)
                    throw new ArgumentException("Пропущено значение параметра запуска.");
                options[key] = args[i];
            }
            string state;
            if (!options.TryGetValue("--state", out state))
                state = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "FolderCamera");
            state = Path.GetFullPath(state);
            options["--state"] = state;
            string profile;
            using (var sha = SHA256.Create())
                profile = Convert.ToBase64String(sha.ComputeHash(Encoding.UTF8.GetBytes(state.ToUpperInvariant()))).Replace('/', '_');
            using (var activate = new EventWaitHandle(false, EventResetMode.AutoReset, @"Local\FolderCamera.Activate." + profile))
            {
                bool first;
                using (var mutex = new Mutex(true, @"Local\FolderCamera.Window." + profile, out first))
                {
                    if (!first) { activate.Set(); return; }
                    using (var engine = new Engine(options))
                    using (var form = new ReceiverForm(engine, activate))
                    {
                        if (background)
                        {
                            using (var context = new ApplicationContext())
                            {
                                form.PrepareBackgroundStart();
                                form.FormClosed += delegate { context.ExitThread(); };
                                Application.Run(context);
                            }
                        }
                        else Application.Run(form);
                    }
                }
            }
        }
        catch (Exception error) { MessageBox.Show(error.Message, Title, MessageBoxButtons.OK, MessageBoxIcon.Error); }
    }
}

internal sealed class Engine : IDisposable
{
    private readonly Process process;
    private readonly StreamWriter input;
    private readonly JavaScriptSerializer json = new JavaScriptSerializer();

    internal Engine(Dictionary<string, string> options)
    {
        var arguments = new List<string>();
        // Use standard Windows command-line quoting, including trailing path separators.
        foreach (var option in options)
        {
            arguments.Add(option.Key);
            string escaped = Regex.Replace(option.Value, @"(\\*)""", "$1$1\\\"");
            arguments.Add("\"" + Regex.Replace(escaped, @"\\+$", "$0$0") + "\"");
        }
        process = Process.Start(new ProcessStartInfo
        {
            FileName = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "FolderCameraEngine.exe"),
            Arguments = string.Join(" ", arguments),
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            StandardOutputEncoding = Encoding.UTF8
        });
        input = new StreamWriter(process.StandardInput.BaseStream, new UTF8Encoding(false)) { AutoFlush = true };
    }

    internal async Task<Dictionary<string, object>> Request(string action, object value)
    {
        input.WriteLine(json.Serialize(new { action = action, value = value }));
        string line = await process.StandardOutput.ReadLineAsync();
        if (line == null) throw new IOException("Движок приёмника завершил работу.");
        var reply = json.Deserialize<Dictionary<string, object>>(line);
        object error;
        if (reply.TryGetValue("error", out error)) throw new InvalidOperationException(Convert.ToString(error));
        return (Dictionary<string, object>)reply["result"];
    }

    public void Dispose()
    {
        try { input.Dispose(); } catch (IOException) { }
        try { if (!process.WaitForExit(6500)) process.Kill(); }
        catch (InvalidOperationException) { }
        finally { process.Dispose(); }
    }
}

internal sealed class ReceiverForm : Form
{
    private readonly Engine engine;
    private readonly EventWaitHandle activate;
    private readonly NotifyIcon tray;
    private readonly System.Windows.Forms.Timer refresh = new System.Windows.Forms.Timer();
    private readonly SemaphoreSlim requests = new SemaphoreSlim(1);
    private readonly Label state = Label();
    private readonly Label address = Label();
    private readonly Label folder = Label();
    private readonly Button toggle = new Button();
    private readonly Button pair = new Button();
    private readonly PictureBox qr = new PictureBox();
    private readonly CheckBox closeToTray = new CheckBox();
    private readonly ListBox phones = new ListBox();
    private readonly ListBox photos = new ListBox();
    private string root, qrData;
    private bool running, keepReceiving, quitting, receivedStatus, resourcesDisposed;

    internal ReceiverForm(Engine engine, EventWaitHandle activate)
    {
        this.engine = engine;
        this.activate = activate;
        Text = WindowsReceiver.Title;
        Icon = System.Drawing.Icon.ExtractAssociatedIcon(Application.ExecutablePath);
        ClientSize = new Size(840, 640);
        MinimumSize = new Size(680, 540);
        StartPosition = FormStartPosition.CenterScreen;
        AutoScaleMode = AutoScaleMode.Font;
        Font = new Font("Segoe UI", 10);

        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 6, Padding = new Padding(14) };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        for (int i = 0; i < 3; i++) layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 68));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 32));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        Controls.Add(layout);

        state.Font = new Font(Font, FontStyle.Bold);
        state.Text = "Запуск приёмника…";
        var status = Flow(state, toggle);
        toggle.AutoSize = true;
        toggle.Text = "Начать приём";
        toggle.Enabled = false;
        toggle.Click += async delegate { await Run(running ? "stop" : "start"); };
        layout.Controls.Add(status, 0, 0);
        layout.SetColumnSpan(status, 2);
        layout.Controls.Add(address, 0, 1);
        layout.SetColumnSpan(address, 2);
        var destination = Flow(
            Button("Сменить папку", ChooseFolder),
            Button("Открыть папку", () => OpenFolder(root)), folder);
        layout.Controls.Add(destination, 0, 2);
        layout.SetColumnSpan(destination, 2);

        pair.AutoSize = true;
        pair.Text = "Создать код подключения";
        pair.Enabled = false;
        pair.Click += async delegate { await Run("pair"); };
        qr.Dock = DockStyle.Fill;
        qr.SizeMode = PictureBoxSizeMode.Zoom;
        qr.BackColor = Color.White;
        qr.AccessibleName = "QR-код подключения";
        var pairing = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, RowCount = 3 };
        pairing.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        pairing.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        pairing.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        var help = Label();
        help.Text = "На телефоне откройте «Настройки → Подключить приёмник» и отсканируйте код.";
        help.MaximumSize = new Size(340, 0);
        pairing.Controls.Add(pair, 0, 0);
        pairing.Controls.Add(help, 0, 1);
        pairing.Controls.Add(qr, 0, 2);
        layout.Controls.Add(pairing, 0, 3);

        phones.Dock = photos.Dock = DockStyle.Fill;
        var devices = new GroupBox { Text = "Подключённые телефоны", Dock = DockStyle.Fill, Padding = new Padding(8) };
        var disconnect = Button("Отключить выбранный", Revoke);
        disconnect.Dock = DockStyle.Bottom;
        devices.Controls.Add(phones);
        devices.Controls.Add(disconnect);
        layout.Controls.Add(devices, 1, 3);
        var recent = new GroupBox { Text = "Полученные снимки", Dock = DockStyle.Fill, Padding = new Padding(8) };
        recent.Controls.Add(photos);
        photos.DoubleClick += delegate
        {
            var selected = photos.SelectedItem as ListEntry;
            if (selected != null) OpenFolder(selected.Value);
        };
        layout.Controls.Add(recent, 0, 4);
        layout.SetColumnSpan(recent, 2);

        closeToTray.Text = "Продолжать приём после закрытия окна";
        closeToTray.AutoSize = true;
        closeToTray.Enabled = false;
        closeToTray.Click += async delegate
        {
            await Run("closeToTray", closeToTray.Checked);
            closeToTray.Checked = keepReceiving;
        };
        var autostart = new CheckBox { Text = "Запускать при входе в Windows", AutoSize = true };
        using (var key = Registry.CurrentUser.OpenSubKey(WindowsReceiver.StartupKey))
            autostart.Checked = key != null && key.GetValue(WindowsReceiver.StartupName) is string;
        autostart.Click += delegate
        {
            try
            {
                using (var key = Registry.CurrentUser.CreateSubKey(WindowsReceiver.StartupKey))
                {
                    if (autostart.Checked)
                        key.SetValue(WindowsReceiver.StartupName, "\"" + Application.ExecutablePath + "\" --background");
                    else key.DeleteValue(WindowsReceiver.StartupName, false);
                }
            }
            catch (Exception error) { autostart.Checked = !autostart.Checked; ShowError(error); }
        };
        var settings = Flow(closeToTray, autostart, Button("Выйти", Quit));
        layout.Controls.Add(settings, 0, 5);
        layout.SetColumnSpan(settings, 2);

        var menu = new ContextMenuStrip();
        menu.Items.Add("Открыть приёмник", null, delegate { Reveal(); });
        menu.Items.Add("Выйти из приёмника", null, delegate { Quit(); });
        tray = new NotifyIcon { Text = WindowsReceiver.Title, Icon = Icon, ContextMenuStrip = menu, Visible = true };
        tray.MouseClick += (sender, args) => { if (args.Button == MouseButtons.Left) Reveal(); };
        tray.MouseDoubleClick += (sender, args) => { if (args.Button == MouseButtons.Left) Reveal(); };

        refresh.Interval = 750;
        refresh.Tick += async delegate
        {
            if (activate.WaitOne(0)) Reveal();
            if (requests.CurrentCount != 0 && (Visible || !receivedStatus)) await Run("status");
        };
        refresh.Start();
    }

    private static Label Label() { return new Label { AutoSize = true, Margin = new Padding(3, 7, 6, 5) }; }
    private static Button Button(string text, Action action)
    {
        var control = new Button { Text = text, AutoSize = true };
        control.Click += delegate { action(); };
        return control;
    }
    private static FlowLayoutPanel Flow(params Control[] controls)
    {
        var layout = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, WrapContents = true };
        layout.Controls.AddRange(controls);
        return layout;
    }
    private static string Field(Dictionary<string, object> row, string key) { return Convert.ToString(row[key]); }
    private static void UpdateList(ListBox target, List<ListEntry> entries)
    {
        bool same = target.Items.Count == entries.Count;
        for (int i = 0; same && i < entries.Count; i++)
        {
            var old = (ListEntry)target.Items[i];
            same = old.Name == entries[i].Name && old.Value == entries[i].Value;
        }
        if (same) return;
        var selected = target.SelectedItem as ListEntry;
        target.BeginUpdate();
        target.Items.Clear();
        target.Items.AddRange(entries.ToArray());
        if (selected != null) target.SelectedIndex = entries.FindIndex(entry => entry.Value == selected.Value);
        target.EndUpdate();
    }
    private sealed class ListEntry
    {
        internal string Name, Value;
        public override string ToString() { return Name; }
    }
    private void Render(Dictionary<string, object> status)
    {
        receivedStatus = true;
        string current = Field(status, "status");
        running = current == "ready";
        closeToTray.Checked = keepReceiving = Convert.ToBoolean(status["closeToTray"]);
        closeToTray.Enabled = true;
        toggle.Text = running ? "Остановить приём" : "Начать приём";
        toggle.Enabled = true;
        pair.Enabled = running;
        string error = Field(status, "error");
        state.Text = running ? "Готов к приёму" : current == "error" ? "Ошибка подключения" : "Приём остановлен";
        address.Text = running ? Field(status, "endpoint") + " · Передач: " + Field(status, "active") :
            error.StartsWith("Connect to") ? "Подключите компьютер к локальной сети Wi-Fi или Ethernet." : error;
        root = Field(status, "root");
        folder.Text = root;
        string data = Field(status, "qr");
        if (data != qrData)
        {
            var old = qr.Image;
            qr.Image = null;
            if (old != null) old.Dispose();
            if (data.Length > 0)
                using (var bytes = new MemoryStream(Convert.FromBase64String(data)))
                using (var image = Image.FromStream(bytes)) qr.Image = new Bitmap(image);
            qrData = data;
        }
        var paired = new List<ListEntry>();
        foreach (Dictionary<string, object> phone in (IEnumerable)status["devices"])
            paired.Add(new ListEntry { Name = Field(phone, "name"), Value = Field(phone, "id") });
        UpdateList(phones, paired);
        var arrivals = new List<ListEntry>();
        foreach (Dictionary<string, object> photo in (IEnumerable)status["recent"])
        {
            string relative = Field(photo, "relativePath");
            arrivals.Add(new ListEntry {
                Name = Path.Combine(relative, Field(photo, "filename")),
                Value = Path.Combine(Field(photo, "root"), relative)
            });
        }
        UpdateList(photos, arrivals);
    }
    private async Task Run(string action, object value = null)
    {
        await requests.WaitAsync();
        try
        {
            if (quitting) return;
            var status = await engine.Request(action, value);
            if (!quitting && !IsDisposed) Render(status);
        }
        catch (Exception error)
        {
            if (quitting || IsDisposed) return;
            if (action == "status") { state.Text = "Ошибка приёмника"; address.Text = error.Message; }
            else ShowError(error);
        }
        finally { requests.Release(); }
    }
    private void Reveal()
    {
        if (quitting) return;
        if (!Visible) Show();
        if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
        Activate();
    }
    internal void PrepareBackgroundStart() { CreateHandle(); }
    private async void ChooseFolder()
    {
        using (var dialog = new FolderBrowserDialog { Description = "Выберите папку для снимков.", SelectedPath = root ?? "" })
            if (dialog.ShowDialog(this) == DialogResult.OK) await Run("root", dialog.SelectedPath);
    }
    private void OpenFolder(string path)
    {
        if (string.IsNullOrEmpty(path)) return;
        try { Process.Start(new ProcessStartInfo(path) { UseShellExecute = true }); }
        catch (Exception error) { ShowError(error); }
    }
    private async void Revoke()
    {
        var phone = phones.SelectedItem as ListEntry;
        if (phone != null && MessageBox.Show(this, "Отключить выбранный телефон?", Text,
            MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes) await Run("revoke", phone.Value);
    }
    private void ShowError(Exception error) { MessageBox.Show(this, error.Message, Text, MessageBoxButtons.OK, MessageBoxIcon.Error); }
    private void Quit() { quitting = true; Close(); }
    protected override void OnFormClosing(FormClosingEventArgs args)
    {
        if (!quitting && keepReceiving && args.CloseReason == CloseReason.UserClosing)
        {
            args.Cancel = true;
            Hide();
        }
        else quitting = true;
        base.OnFormClosing(args);
    }
    protected override void Dispose(bool disposing)
    {
        if (disposing && !resourcesDisposed)
        {
            resourcesDisposed = true;
            refresh.Dispose();
            if (tray != null)
            {
                tray.Visible = false;
                tray.ContextMenuStrip.Dispose();
                tray.Dispose();
            }
            if (qr.Image != null) qr.Image.Dispose();
        }
        base.Dispose(disposing);
    }
}
