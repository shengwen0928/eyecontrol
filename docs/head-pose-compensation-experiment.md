# 頭部姿態補償對照實驗（review 分支限定，未驗證）

## 背景

2026-11-23 實機診斷數據顯示：頭部仰角變化時，原始預測跟著大幅移動
（12:17 段 pitch 由低到高分五組，rawX 平均約 1000 → -185；yaw 與 rawX
相關 -0.79）。使用者回報「頭的仰角影響很大」。

## 假說

`GazeModel.predict()` 裡頭部姿態以兩條路徑重複進入預測：

1. ridge 權重直接吃特徵 f[7]（pitchRot）、f[8]（yawRot）；
2. 顯式幾何項再疊加 `deltaYaw × screenW × kx`、`-deltaPitch × screenH × ky`。

且 kx/ky 是在九點校正（頭部幾乎不動、delta 幾乎為 0）時聯合學出的，
屬弱識別係數（clamp ±3.0），頭一動就全額作用。f[7]/f[8] 又經 roll 旋轉
混合，仰角會漏進水平方向。

## 本次變更（僅對照實驗，不得視為定稿行為）

- `GazeModel.predict()` 新增參數 `headPoseCompensation: Boolean = true`；
  傳 `false` 時跳過 kx/ky 幾何項、只輸出 ridge 映射。預設 true，
  現有呼叫端（校正、評估、訓練）行為不變。
- `GazeAccessibilityService` 兩處即時呼叫點（主取樣路徑、
  `updateRecoveryTarget`）傳 `headPoseCompensation = false`。
- 另含編譯修復：`GazeAccessibilityService.kt` 第 1027 行（舊行號）多一個
  右大括號致類別提前關閉、CI 出 1,208 個連帶編譯錯誤；已刪除該行。
  （CI Build APK #31，run 45114212818，日誌證實全部錯誤皆為此連帶造成。）

## A/B 方法

1. 同一份校正模型、同一支手機、同一姿勢範圍：先裝 v0.5.9（補償開啟），
   做一組「固定注視＋只動仰角」動作，記錄 gaze 上傳段。
2. 再裝本分支建出的對照 APK（補償關閉），重複同一組動作，記錄 gaze 上傳段。
3. 比較兩段在相同 pitch 變化下的 rawX/rawY 漂移幅度與游標箝制情形。
4. 臉率判讀須區分「沒看螢幕」與「看著卻偵測不到」：只拿「看著螢幕」
   的段落比較，沒看螢幕的段落不計入。

## 判定

- 關閉後漂移明顯縮小 → kx/ky 幾何項是主因之一，進一步決定：保留開關、
  改預設、或重訓模型（例如校正時納入多頭姿樣本）。
- 關閉後漂移不變 → 主因在 ridge 權重（f[7]/f[8]）或特徵本身，幾何項無辜，
  下一步改從模型／特徵下手，不得直接認定補償有害。

## 回退

刪除兩處 `headPoseCompensation = false`（恢復預設 true）即回退；
或刪除 `predict()` 的參數與分支（連同本文件與對應測試一併移除）。
合併進 main 前須經擁有者決定；本分支維持「禁止發布」。
