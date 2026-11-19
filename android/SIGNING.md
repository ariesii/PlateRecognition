# Debug APK 固定簽章

GitHub Actions 的 Android debug APK 使用 `ariesii/PlateRecognition` 的兩個 Repository Secrets：

- `PLATE_DEBUG_KEYSTORE_B64`：以 base64 編碼的 PKCS#12 keystore，私鑰 alias 為 `plate-debug`；
- `PLATE_DEBUG_KEYSTORE_PASSWORD`：keystore／alias 的密碼。

金鑰由本機 OpenSSL 隨機產生，驗證 PKCS#12 後以 `gh secret set` 加密寫入 GitHub Secrets；產生時的 PEM／PKCS#12 暫存檔在完成後已移除，**公開原碼只有證書指紋，不含私鑰或密碼**。簽署憑證的 SHA-256（DER）為：

```text
d359e1226c9ef65b8d034fc52c5db9b1675632633cfe8ed3db6df25b82df102a
```

CI 必須成功解碼 keystore、簽署 APK，並由 Android `apksigner` 驗證 APK 內簽署證書與此指紋相同才會上傳 artifact。Secrets 不完整時建置必須失敗，不應退回臨時 debug key。本機沒有這兩個環境變數時仍使用 Android Studio 預設 debug 簽章；本機 APK 與 GitHub APK 不能互相覆蓋升級。

**升級邊界：**先前從臨時 CI runner 取得的 debug APK 採不同簽章，無法直接覆蓋安裝此固定簽章的第一次 APK。若 Android 顯示 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，請先備份需要的資料；卸載舊 App 會清除其 SQLite 事件紀錄與每日去重鍵。從安裝第一個固定簽章版起，未來同樣來源的 APK 才能直接覆蓋、保留 App 資料。GitHub Secrets 無法讀回私鑰；若它們被移除或換掉，後續簽章連續性無法保證。此金鑰僅供 debug 測試，不應作為正式商店版簽署金鑰。
