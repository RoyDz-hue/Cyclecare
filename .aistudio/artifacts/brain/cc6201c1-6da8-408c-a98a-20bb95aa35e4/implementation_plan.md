# Wireless ADB Debugging Plan (Android 11+ via Laptop Terminal)

This plan outlines the exact terminal commands and device configuration steps to reconnect and debug the CycleCare app over Wi-Fi from your laptop terminal when USB debugging is unavailable.

---

## Background & Common Pitfall on Android 11+
Because you have already paired your device previously:
- The **Pairing Port** (used with `adb pair`) is one-time and closed after pairing.
- The **Connection Port** under **Wireless debugging** changes dynamically each time Wi-Fi reconnects or the toggle is flipped (e.g., `192.168.1.45:39481`, not standard port `5555`).
- Attempting `adb connect <ip>` without the active 5-digit dynamic port results in `Connection refused` or `failed to connect`.

---

## Proposed Execution Steps

### Step 1: Verify Phone Wi-Fi & Obtain Current Dynamic Port
1. On your Android phone, connect to the **same Wi-Fi network** as your laptop.
2. Open **Settings** > **Developer options** > **Wireless debugging**.
3. Ensure the **Use wireless debugging** toggle is turned **ON** (allow permission if prompted).
4. Note the exact IP and port listed directly on the main screen under **"IP address & Port"** (e.g., `192.168.X.X:XXXXX`).
   *(Do **not** tap "Pair device with pairing code" unless the laptop was forgotten).*

### Step 2: Reset ADB Server on Laptop Terminal
Run these commands in your laptop terminal (PowerShell, Terminal, or Bash):
```bash
adb kill-server
adb start-server
```

### Step 3: Connect over Wi-Fi
Run the connect command using the exact IP and 5-digit port noted in Step 1:
```bash
adb connect <PHONE_IP>:<CURRENT_PORT>
```
*Expected Output:*
```
connected to 192.168.X.X:XXXXX
```

Verify your device is online:
```bash
adb devices
```
*Expected Output:*
```
List of devices attached
192.168.X.X:XXXXX    device
```

*(If status shows `unauthorized`, look at your phone's screen and tap "Always allow on this network").*

### Step 4: Install and Debug CycleCare
Once connected over Wi-Fi, you can deploy and debug directly from your laptop:
1. **Install current debug APK:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. **Launch MainActivity:**
   ```bash
   adb shell am start -n com.example/.MainActivity
   ```
3. **Stream live Logcat logs filtered for CycleCare:**
   ```bash
   adb logcat -s CycleCare:V SupabaseAuth:V AndroidRuntime:E
   ```

---

## Troubleshooting Contingencies
- **If connection times out or fails:**
  - Turn the phone's **Wireless debugging** toggle **OFF** and then **ON** again to generate a fresh port.
  - Verify your Wi-Fi is not on a "Guest Network" with Client Isolation enabled (which prevents laptop-to-phone communication).
  - If re-pairing is needed: In phone's Wireless debugging, tap **"Pair device with pairing code"**, note the 6-digit code and pairing port, and run `adb pair <IP>:<PAIRING_PORT> <CODE>`.
