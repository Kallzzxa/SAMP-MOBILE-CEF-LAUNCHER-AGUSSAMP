# SAMP MOBILE CEF LAUNCHER AGUSSAMP

Launcher SA-MP Mobile berbasis Android dengan dukungan CEF (Chromium Embedded Framework), yang dirancang untuk memberikan pengalaman bermain multiplayer GTA: San Andreas Mobile dengan antarmuka HTML.

Dikembangkan dan dikelola oleh **AgusSamp**.

## Fitur

- Launcher SA-MP Mobile.
- Download data game otomatis di dalam aplikasi saat pertama dibuka: paket dipilih otomatis sesuai GPU (Adreno/DXT, Mali/ETC, PowerVR/PVR) dari MediaFire, tanpa perlu pilih manual — lengkap dengan progress download dan ekstrak.
- Dukungan CEF untuk menampilkan antarmuka berbasis HTML.
- Integrasi aset HTML melalui WebView.
- **Dukungan CefMobile (UI web dari server):** kalau server memakai plugin CefMobile, launcher otomatis download file HTML/CSS/JS dari `scriptfiles/cef-mobile/` di server lewat HTTP port **8778** saat connect, lalu simpan di cache `<gameDir>/SAMP/cef-mobile/` — relog tidak download ulang kalau tidak ada update (cek versi + CRC32 per file). UI tampil fullscreen lewat WebView (browser id 65000) dan event dari halaman (`window.cef.emit`) dikirim balik ke server. Kalau server tidak punya plugin CefMobile, launcher diam dan game jalan seperti biasa.
- Pengaturan IP dan port server.
- Dukungan native Android.
- Mendukung beberapa arsitektur ARM.

## Persyaratan

- Android Studio
- JDK 17
- Android SDK 35
- Android NDK 25.1.8937393
- CMake

## Arsitektur yang Didukung

- `armeabi-v7a`
- `arm64-v8a`

## Persiapan & Build

1. Clone atau unduh repository ini.
2. Buka folder proyek menggunakan Android Studio atau Visual Studio Code.
3. Pastikan Android SDK, NDK, dan CMake sudah terpasang sesuai versi yang dibutuhkan.
4. Pastikan JDK 17 sudah dikonfigurasi dengan benar.
5. Lakukan sinkronisasi Gradle.
6. Atur konfigurasi signing sendiri jika ingin membuat Release APK.
7. Jalankan perintah Gradle untuk membangun aplikasi.

## Build Menggunakan VS Code

Buka terminal VS Code (`Ctrl + Shift + ~`).

Masuk ke direktori proyek:

```powershell
cd C:\Users\Guz\Downloads\superfinemobilerp
```

**Build Debug APK:**

```powershell
.\gradlew.bat assembleDebug
```

**Build Release APK:**

```powershell
.\gradlew.bat assembleRelease
```

**Membersihkan hasil build sebelumnya:**

```powershell
.\gradlew.bat clean
```

**Clean sekaligus Build Debug:**

```powershell
.\gradlew.bat clean assembleDebug
```

### Lokasi Hasil APK

Debug APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Release APK:

```text
app\build\outputs\apk\release\app-release.apk
```

## Konfigurasi HTML CEF / WebView

Lokasi penyimpanan file HTML untuk antarmuka WebView/CEF:

```text
app\src\main\assets\html
```

Letakkan file berikut di dalam direktori tersebut:

- HTML
- CSS
- JavaScript
- Gambar dan aset pendukung lainnya

Gunakan direktori ini untuk mengelola tampilan dan antarmuka HTML launcher.

## Konfigurasi IP dan Port Server

Untuk mengubah IP dan port server SA-MP, buka file:

```text
app\src\main\java\ro\alynsampmobile\launcher\ui\fragment\InformationPageFragment.java
```

Cari konfigurasi berikut:

```java
private static final String DEFAULT_HOST = "192.168.42.41";
private static final int DEFAULT_PORT = 7777;
```

Ubah sesuai IP dan port server yang digunakan.

Contoh:

```java
private static final String DEFAULT_HOST = "IP_SERVER_KAMU";
private static final int DEFAULT_PORT = 7777;
```

## Persyaratan Game

**Penting:** Launcher ini membutuhkan GTA: San Andreas Android versi **2.10** beserta data game yang sesuai agar dapat berjalan dengan baik.

## Kredit & Pengembang

**Developer:** AgusSamp

**Discord Resmi:**

https://discord.gg/FEczPuxwrt

**Grup WhatsApp Resmi:**

https://chat.whatsapp.com/KrecviKYS9a47HrJIZkxHF

---

Dikembangkan oleh **AgusSamp** dan **Suzu**.
