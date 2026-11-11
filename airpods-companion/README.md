# PodsPanel

在 Android 手機上使用 AirPods 的小工具：電量彈窗、等化器、讓鬧鐘在耳機裡響。不需要 root。

這是非官方的個人專案，與 Apple 沒有任何關係；AirPods 是 Apple Inc. 的商標。
App 沒有網路權限，不會收集或傳送任何資料。

## 功能

| 功能 | 說明 |
| --- | --- |
| 電量 | 左耳、右耳、充電盒的電量與充電狀態，顯示在 App、通知列，以及（HyperOS 3）螢幕上方的超級島。 |
| 電量彈窗 | 耳機連線、或在手機旁打開充電盒蓋時，從螢幕下方滑出卡片。 |
| 降噪切換 | 關閉／降噪／通透／自適應。需要「直接連線」可用，且耳機本身支援。 |
| 等化器 | 10 段（31 Hz–16 kHz，每段 ±12 dB），內建原音、重低音、人聲、高音、流行、Podcast 六組預設，也可以自己拉。 |
| 鬧鐘在耳機響 | 時鐘 App 的鬧鐘響起時，耳機同步播放鈴聲；在時鐘 App 關掉或延後，耳機也跟著停。 |
| 取下自動暫停 | 取下任一耳暫停播放，兩分鐘內戴回去繼續。預設關閉。 |

App 在背景常駐，不需要開著畫面；耳機一連線就會更新，開啟 App 時會先顯示上次讀到的電量。

## 安裝

1. 到這個專案的 [Releases](../../releases) 頁面，下載最新的 `PodsPanel-x.y.z.apk`。
2. 在手機上開啟 APK，依提示允許「安裝未知應用程式」。
3. 開啟 PodsPanel，照「完成設定」卡片逐項允許：

   | 項目 | 用途 |
   | --- | --- |
   | 藍牙權限 | 讀取 AirPods 的電量與狀態。必要。 |
   | 通知 | 在通知列顯示電量。 |
   | 顯示在其他應用程式上層 | 電量彈窗。沒開的話改用通知顯示。 |
   | 不限制背景用電 | 避免系統把背景服務關掉。 |
   | 自動啟動（HyperOS） | 重開機後自己啟動。 |

   小米／HyperOS 另外建議：在「最近使用的 App」畫面長按 PodsPanel 並上鎖，避免清除背景時被關掉。

需要 Android 9 以上。開發時針對的是 AirPods 5 與 HyperOS 3（Android 16）；其他 AirPods、AirPods Pro、AirPods Max 與部分 Beats 也認得，但沒有逐一實測。

## 各功能的做法與限制

### 電量與耳機狀態：兩個來源

**直接連線（優先）。** iPhone 是透過一條專屬的藍牙通道（AAP）跟 AirPods 溝通，這條通道提供精確到 1% 的電量、可靠的配戴狀態和降噪模式。
PodsPanel 在耳機連線時會嘗試開啟同一條通道。Android 沒有公開的方法可以對耳機開這種通道，所以用的是系統未公開的介面；
能不能連上取決於手機的藍牙系統，部分 Android 版本無法跟 AirPods 完成連線協商。連不上時不影響其他功能，App 會改用下面的廣播。

**藍牙廣播（備援）。** AirPods 會持續廣播自己的狀態，其中型號、電量、是否在耳朵上、盒蓋開關沒有加密，任何手機都讀得到。限制是：

- 電量以 **10% 為單位**。
- 充電盒電量只有在至少一隻耳機放在盒內時才會廣播；其他時候顯示上次讀到的數值。
- 廣播不帶「這是誰的耳機」的資訊，所以用訊號強度判斷：只追蹤離手機最近的那一副，開蓋彈窗也只在訊號夠強時才出現。
- 不同手機收得到這類廣播的掃描方式不一樣（部分 HyperOS 機型用預設方式就收不到）。耳機已連線卻幾秒內沒有廣播時，App 會同時用所有已知的方式掃描約十秒，之後固定使用收得到的那一種；其中「不過濾」的方式只有螢幕亮著時才有資料。

「進階」裡的診斷資訊會顯示兩個來源各自的狀態。

### 超級島（HyperOS 3）

耳機連線時，常駐通知會改以超級島呈現：鏡頭左側是左耳電量、右側是右耳電量；被其他內容擠成小圓點時，耳機圖示外圈顯示較低那一耳的電量。
超級島由系統決定哪些 App 可以使用。如果 App 內顯示「系統尚未允許」，請到這個 App 的通知設定開啟「焦點通知」；
沒有這個選項的系統版本只開放給小米核准的 App，電量會維持顯示在通知列。

### 等化器

AirPods 本身沒有可以從手機設定的等化器（iPhone 上的 EQ 也是在手機端處理），所以這裡是在聲音送到耳機之前，由手機套用。
有兩種套用方式：

- **全域模式**（預設）：套用到所有 App 的聲音。部分手機廠商會擋掉或繞過這種方式。
- **逐一套用模式**：對每個播放器個別套用。只對會回報播放狀態的 App 有效（Spotify、YouTube Music、Apple Music 等；YouTube 不會），而且開啟後要重新啟動音樂 App。

如果手機內建的音效（例如杜比全景聲）同時開著，兩者可能互相影響，建議擇一使用。

### 鬧鐘

Android 會告訴所有 App「現在有哪幾類聲音正在播放」。偵測到鬧鐘類的聲音開始播放時，PodsPanel 就用一般媒體的方式在耳機裡再播一次鈴聲，鬧鐘聲音停止時跟著停。
因此它跟時鐘 App 是哪一個無關，也不需要自己設定鬧鐘時間。

- **耳機也響**：耳機播放鈴聲，手機喇叭照常響。如果系統本來就會把鬧鐘送到耳機，就不重複播放。
- **盡量只在耳機**：耳機播放鈴聲，並在鬧鐘期間把手機的鬧鐘音量降到系統允許的最低，結束後恢復。多數手機的鬧鐘音量不能降到 0，所以喇叭可能仍有很小的聲音。為了避免「耳機放在盒子裡、喇叭又被調小」而睡過頭，只有偵測到耳機戴在耳朵上時才會降低喇叭音量。
- 讀不到時鐘 App 為各個鬧鐘設定的鈴聲，耳機一律播放在 PodsPanel 裡選的鈴聲（預設是系統預設鬧鐘鈴聲）。
- 耳機鬧鐘音量在 App 內設定，響鈴時暫時調整媒體音量，結束後恢復。

## 開發

```bash
cd airpods-companion
./gradlew testDebugUnitTest   # 測試
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 21 與 Android SDK（platform 36）。

### 發佈

正式版的簽章金鑰不放在版本庫裡。發佈由 `PodsPanel release` 這個 workflow 手動執行：
它讀取版本庫的兩個 secret（`PODSPANEL_KEYSTORE_BASE64`、`PODSPANEL_KEYSTORE_PASSWORD`），
編譯、簽章 `app/build.gradle.kts` 裡的版本，並建立對應的 GitHub Release。
發新版前記得先調高 `versionCode` 與 `versionName`。

自己 fork 的話，請產生自己的金鑰（別名用 `podspanel`）並設定同名的 secret；
簽章不同的 APK 無法覆蓋安裝原本的版本。

程式結構：

| 位置 | 內容 |
| --- | --- |
| `core/` | 不依賴 Android 的邏輯：廣播解析、耳機追蹤、等化器頻段計算、鬧鐘與自動暫停的判斷。 |
| `service/` | 背景服務：藍牙掃描、等化器、鬧鐘同步。 |
| `ui/` | 主畫面與彈窗。 |

## 致謝

- AirPods 廣播格式、各型號代碼與 AAP 訊息格式參考自 [CAPod](https://github.com/d4rken-org/capod)、[LibrePods](https://github.com/kavishdevar/librepods) 與 [OpenPods](https://github.com/adolfintel/OpenPods) 的公開資料；測試用的 AirPods 5 廣播樣本取自 CAPod 的測試集。
- 超級島的通知參數由 [HyperIsland ToolKit](https://github.com/D4vidDf/HyperIsland-ToolKit)（Apache 2.0）產生。
- 呼叫未公開介面使用 [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)（Apache 2.0）。
