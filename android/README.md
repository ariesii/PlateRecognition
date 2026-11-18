# Android 即時車牌辨識（實作草稿）

本目錄是在 [pcb9382/PlateRecognition](https://github.com/pcb9382/PlateRecognition) 原始碼旁新增的 **Kotlin / CameraX / ONNX Runtime** 手機實作，不使用桌面版 CUDA／TensorRT `.so`。上游程式依 [MIT 授權](../LICENSE)；發佈 APK 前仍應自行確認原始 ONNX 模型所涉及的第三方授權。**目前未完成 Android 裝置與 APK 的實測驗收**，請勿將此目錄視為已驗證的商用辨識產品。

## 操作與規則

- 開啟 App 並允許相機權限後，後鏡頭持續預覽；CameraX 丟棄落後影格，模型在背景執行緒逐張分析，畫面會顯示最近一次車牌結果與實測推論時間。這是連續串流的即時*更新*，並不保證每秒 30 次辨識。預覽框與實際儲存的標記以分析影格為準，部分裝置的預覽裁切可能造成預覽框少量偏移。
- 同一文字連續兩個分析影格辨識一致（兩影格間不超過 4 秒）、包含拉丁字母和數字且長度 5–10，才會自動儲存當時的**完整分析影格** JPEG，標上所有偵測框、辨識字串和本機時間。不是延後拍攝的高畫素感光元件照片。
- 去掉空格及分隔符並轉大寫的車牌文字 + **裝置當地日曆日期**構成唯一索引。同一 App 安裝、同一裝置上，同一車牌當天只會觸發一次存檔；App 重啟仍有效，隔天可以再存。多個不同車牌**同時首次確認**時共用一張標記完整畫面，資料庫分別記錄每個車牌；照片在手機相簿 `Pictures/PlateRecognition/`。
- 清除 App 資料或重新安裝會清除去重紀錄，但相簿照片可能仍在；手動刪除照片不會清除當天紀錄。若 OCR 把同一實體車牌讀成不同字串，軟體無法嚴格判定它們相同；這是文字去重的限制，而非保證不會重複拍到同一輛車。
- 除相機外不索取照片讀取、寫入儲存空間或網路權限；Android 10+ 使用 MediaStore 存檔。沒有自動上傳，也沒有選圖與手動拍照。

## 模型與管線

| 用途 | 上游檔案 | 大小 | SHA-256 |
| --- | --- | ---: | --- |
| YOLOv5 車牌／四角檢測 | `../PLateDetection_yolov5/test/yolov5plate.onnx` | 3,838,666 B | `472ec204f4866824287cb4093cf11e5b6ef355a35447f3969120288dd01b0161` |
| PP-OCRv6 medium 文字辨識 | `../PlateRecognitionOCRV6/models/medium/inference.onnx` | 76,554,979 B | `9c09abf0957f7968c7586464b7397b84ad2387a0497a351af40e9acc71b673ba` |

Gradle 在建置時從上游相對路徑複製這兩個模型到 `app/build/generated/modelAssets/`，不把相同的大模型再提交一次。第一次啟動會把模型從 APK assets 複製到 App 私有空間供 ONNX Runtime 開啟，因此 APK 和私有儲存空間都需預留模型容量。Detector 為 RGB 640×640 letterbox，OCR 為透視校正之 BGR 高 48／動態寬度；77 字元 sparse index 來自上游 `PlateRecognitionOCRV6/src/OCRV6Text.cpp`。前處理與映射須與原始模型一致，不代表上游宣稱的 99%+ 精度。台灣及其他地區需以實際現場樣本驗證；本 OCR 的允許字元並不涵蓋所有國家／特殊牌型。

## 建置與目前障礙

需要 JDK 17、Android SDK 35、支援 API 29+ 的 Android 手機，以及可以下載 Google Maven/Maven Central 相依套件的開發機。Android Studio 開啟此 `android/` 資料夾即可同步 Gradle；正常情況下 Windows 可由此目錄執行 `gradlew.bat :app:assembleDebug` 並以 Android Studio 安裝 `app/build/outputs/apk/debug/app-debug.apk`。

**目前採用 GitHub Actions 遠端編譯，不在這台電腦安裝 JDK／Android SDK。**將變更上傳到自己的 GitHub fork 後，[`Android debug APK`](../.github/workflows/android.yml) 工作流程會在 Ubuntu 使用 JDK 17、Android SDK 35 驗證模型 SHA-256 並執行 `:app:assembleDebug`，成功後在該次 Actions 的 `plate-recognition-debug-apk` artifact 下載 APK。官方 Gradle 8.9 wrapper 已取得，`gradle-wrapper.jar` 的 SHA-256 與[官方公告](https://gradle.org/release-checksums/)相符；本機仍沒有 JDK／Android SDK，也尚無連上的 Android 裝置，因此**本機**不能執行 Gradle 編譯或實機驗收。上游模型的檔案大小與 SHA-256 已核對；ONNX Runtime 載入、現場攝影機路徑、Android UI 自動化及跨日／同日去重的實機測試均尚未完成。ARTEMIS 診斷目前顯示沒有裝置／模擬器，且需要在其 `.env` 設定多模態模型 API key 後重新載入 MCP server；請勿在對話貼上 key。

要完成驗收，請準備 Android Studio/JDK/SDK 與已授權 USB 偵錯的手機，並依照 [CameraX 分析文件](https://developer.android.com/media/camera/camerax/analyze) 檢查串流時序及 [MediaStore 文件](https://developer.android.com/training/data-storage/shared/media) 檢查相簿存檔。相機畫面必須在裝置上實測，不能以靜態照片測試取代。
