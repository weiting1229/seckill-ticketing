# 海報生圖操作手冊(Poster Generation Runbook)

> ## ⚠️ 2026-08-22:§3 之後的內容已由 poster-forge 專案取代
>
> 本手冊 §1–§2(硬體前提、環境建置、cu130 那個坑)**仍然有效且重要**。
>
> 但 §3 之後的「選一個最好的模型 → 用八組模板批次生圖 → 挑圖 → 轉檔 → 交付」整條流程,
> 已被 poster-forge 專案(`C:/Users/USER/Documents/poster-forge`,決策紀錄見其 `CLAUDE.md` §2) 取代:
>
> | 本手冊 | 現況 |
> |---|---|
> | §3 候選模型 / §4 選型協定 | ❌ 目標從「選一個模型」改成「**建一個風格庫**」,一筆風格自帶模型與參數 |
> | §4.4 可用率與每張可用圖成本 | ✅ **保留並升級**——`pnpm batch --stats` 自動計算 |
> | §4.6 記錄表模板 | ✅ 已填入實測值,見該節標註 |
> | §5.1 批次生成 | ❌ 改用 `pnpm batch` |
> | §5.2 挑圖 | ❌ 改用 `pnpm pick`(三級評分、裁切指示線、即時合成預覽) |
> | §5.3 `magick mogrify` | ❌ **這條指令跑不起來**,`magick` 不在本機 PATH。改用 `sharp` |
> | §6 交付 | ❌ 改用 `pnpm compose` |
> | §7 Ollama 產文案詞素池 | ✅ 仍有效,與本專案無關 |

> **定位**:這是一份**操作手冊**,不是計畫。計畫執行完就歸檔,這份文件則是每次要新增樂團海報
> 時都會回來看的東西。
>
> **相關文件**
> - 海報如何被使用(registry、三層 fallback、版權紅線、prompt 模板)
>   → [`plans/2026-08-17-demo-event-seeder.md`](plans/2026-08-17-demo-event-seeder.md) §6
> - 圖片如何上傳與提供(後台管理、Caddy 路由)
>   → [`plans/2026-08-18-poster-dynamic-management.md`](plans/2026-08-18-poster-dynamic-management.md)
>
> **最後更新**:2026-08-18(**步驟 0 已實跑**,§2 已依實測修正;§3 之後仍為規劃)

---

## 1. 硬體前提

| 項目 | 實測值 | 影響 |
|---|---|---|
| GPU | NVIDIA GeForce RTX 5070 | Blackwell 架構(sm_120),見 §2 的版本坑 |
| VRAM | 12227 MiB(約 12 GB) | 決定模型能不能塞下、能不能批次 |
| 系統記憶體 | 32 GB | 充足,不是瓶頸 |
| 驅動 | 591.86 | — |

12 GB 是這整份文件的核心約束:**SDXL 系(約 6.6 GB)有大量餘裕可批次 4 張;
FLUX 系 12B 要靠量化才塞得下,而且塞得下不代表跑得順。**

---

## 2. 步驟 0:環境建置

### 2.1 必踩的坑:RTX 50 系列需要 cu128

RTX 5070 是 **Blackwell(sm_120)**。網路上流傳的 ComfyUI portable 包與各種一鍵安裝器,
不少仍綁 **PyTorch cu121**,那個建置的編譯目標不含 sm_120。症狀是安裝一切正常、模型也載入了,
**但按下生成的瞬間**報:

```
CUDA error: no kernel image is available for execution on the device
```

這不是設定問題、不是顯卡壞掉、也不是 VRAM 不足——是**二進位檔裡根本沒有你這張卡的核心**。
裝之前先確認是 **CUDA 12.8+ / PyTorch 2.7+(cu128)** 的建置。

驗證方式(在 ComfyUI 的 Python 環境裡):

```bash
python -c "import torch; print(torch.__version__, torch.version.cuda); print(torch.cuda.get_arch_list())"
```

`get_arch_list()` 的輸出要包含 `sm_120`(或 `sm_90` 以上並標示 Blackwell 相容)。
沒有就換建置,不要試圖用其他方式繞。

### 2.1b 實跑修正:cu128 能跑,但對 ComfyUI 0.33 已經不夠

**2026-08-18 實跑推翻了上面「裝 cu128 就對了」的結論。**

cu128 確實解決了 §2.1 的 `no kernel image` 問題(`get_arch_list()` 含 `sm_120`,fp16 matmul 實測通過)。
但 ComfyUI 0.33.0 開機時會警告:

```
WARNING: You need pytorch with cu130 or higher to use optimized CUDA operations.
If you are on nvidia 20 series and above it is required that you update your pytorch to cu130 or higher.
```

原因在 `comfy/quant_ops.py`,是一道**硬性版本閘門**:

```python
cuda_version = tuple(map(int, str(torch.version.cuda).split('.')))
if cuda_version < (13,):
    ck.registry.disable("cuda")
```

`torch.version.cuda` 只要小於 13,`comfy-kitchen` 的 **CUDA 後端就被整個關掉**,退回 `eager` 純 PyTorch 路徑。
被關掉的是 fp8 / nvfp4 / int8 的融合量化 kernel(`scaled_mm_nvfp4`、`quantize_per_tensor_fp8` 等)。

**為什麼這對本專案特別致命**:12 GB 這個約束(§1)正是逼我們走量化路線的原因——§3 的 FLUX.1-schnell
要靠 GGUF/fp8 才塞得下。而 cu128 剛好關掉的就是量化加速。也就是說,**最需要它的那條路線,恰好是它失效的路線**。
§4.4 的「單張耗時」若在 cu128 下量,量到的是被降級後的數字,拿去算「每張可用圖成本」會系統性高估。

**做法**:改裝 cu130,但**不動 torch 版本號**——只換 CUDA runtime。理由是 `comfy-kitchen` 帶 C++ 擴充,
跨 torch 版本升級有 ABI 不匹配風險,同版號換 runtime 是最小變更面。

```bash
pip install --force-reinstall --no-deps   torch==2.11.0+cu130 torchvision==0.26.0+cu130 torchaudio==2.11.0+cu130   --index-url https://download.pytorch.org/whl/cu130
```

驗收標準(缺一不可):

| 檢查 | 期望 |
|---|---|
| `torch.version.cuda` | `13.0` |
| `torch.cuda.get_arch_list()` | 含 `sm_120` |
| 開機日誌 | **沒有** `cu130 or higher` 警告 |
| 開機日誌 | `comfy_kitchen backend cuda: {'available': True, 'disabled': False` |

> 注意:cu130 需要驅動支援 CUDA 13。本機驅動 591.86 報 CUDA 13.1,足夠。
> 若換到舊驅動的機器,先跑 `nvidia-smi` 確認 CUDA 版本 ≥ 13.0,不然要退回 cu128 並接受量化降速。

### 2.2 為什麼用 ComfyUI

| 需求 | ComfyUI | WebUI (A1111/Forge) | Ollama |
|---|---|---|---|
| 批次佇列(160 張跑整晚) | ✅ 原生 | ⚠️ 有限 | ❌ |
| API 模式(腳本驅動) | ✅ | ⚠️ | — |
| FLUX 量化(GGUF)支援 | ✅ | ⚠️ | — |
| Windows 可用 | ✅ | ✅ | ❌ **生圖僅 macOS,且 v0.32.6 已移除** |

Ollama 的部分見 §6 補充說明——**它在這個專案有另一個位置,但不是生圖。**

### 2.3 目錄與模型放置

依 ComfyUI 慣例:`models/checkpoints/`(SDXL)、`models/unet/` 或 `models/diffusion_models/`
(FLUX)、`models/clip/`(T5 / CLIP text encoder)、`models/vae/`。
下載模型前先確認授權(見 §3 表格),尤其若日後想把成果用在非個人展示的場合。

實跑補充:ComfyUI 0.33.0 clone 下來時這些目錄**已經全部存在**,不用自己建。
另外 text encoder 現在的正規位置是 **`models/text_encoders/`**,`models/clip/` 僅保留為舊名相容;
新下載的 T5 / CLIP 放 `text_encoders/`。

### 2.4 實跑紀錄(2026-08-18)

| 項目 | 實際值 |
|---|---|
| 安裝路徑 | `C:\Users\USER\Documents\ComfyUI` |
| ComfyUI | 0.33.0(git clone,`deploy_environment: local-git`) |
| Python | 3.12.10,venv 位於 `ComfyUI/.venv` |
| torch | 2.11.0+cu130 / torchvision 0.26.0+cu130 / torchaudio 2.11.0+cu130 |
| `get_arch_list()` | `['sm_75','sm_80','sm_86','sm_90','sm_100','sm_120']` |
| 裝置 | `cuda:0 NVIDIA GeForce RTX 5070 : cudaMallocAsync`,Total VRAM 12227 MB |
| attention | `Using pytorch attention`(未裝 xformers / sage-attention) |
| 節點數 | 855(`/object_info`),`KSampler` 存在 |
| 啟動 | `ComfyUI\run-comfyui.bat` → http://127.0.0.1:8188 |

**未安裝、之後可能要補的**:

- ImageMagick(`magick`)—— §5.3 轉檔要用,**本機實測未安裝**,批次挑完圖前要補。
- `ComfyUI-GGUF` 自訂節點 —— 只有走 FLUX.1-schnell 的 GGUF 路線才需要。該候選已於 §3.1 排除,目前不需要。
- 模型權重 —— **已下載完成,見 §3.1**。

---

## 3. 步驟 1:候選模型

| 模型 | 參數 | 12 GB 可行性 | 授權 | 適合本用途的理由 |
|---|---|---|---|---|
| **SDXL 系社群 checkpoint** | 3.5B | fp16 約 6.6 GB,**餘裕大,可批次 4 張** | CreativeML Open RAIL++-M | 霓虹/舞台光/抽象幾何有大量現成風格權重可站上去 |
| **FLUX.2 Klein 4B** | 4B | 舒適 | Apache 2.0(4B) | 新架構、體積小、授權乾淨 |
| **FLUX.1-schnell** | 12B | GGUF Q6_K 約 9.8 GB 或 fp8,**偏緊** | Apache 2.0 | 4 步出圖,質感好 |
| **Z-Image Turbo** | 6B | 官方標 **12–16 GB**,你在下緣 | Apache 2.0 | 中英文字渲染強(**僅對含文字路線有意義**) |
| FLUX.1-dev | 12B | 同 schnell,步數多更慢 | 非商用授權 | 品質天花板最高,但最慢 |

⚠️ **不要直接照抄任何人的排名表(包括這張)。** 星等與排名是主觀的,而且多半沒有針對
「抽象舞台氛圍背景、16:9、不要文字」這個具體題目測過。下一節的協定就是為了取代排名表。

**特別提醒 Z-Image Turbo**:它的招牌賣點是中英雙語文字渲染。**若走純背景路線,這個強項完全
用不到**(prompt 的負面提示第一行就是排除所有文字),排名再高也不代表適合。

### 3.1 實跑:實際下載了什麼(2026-08-18)

**候選收斂為三個**:SDXL 系、FLUX.2 Klein 4B、Z-Image Turbo。

- **FLUX.1-dev 排除**:非商用授權,§4.7 規則 5 本來就會淘汰它,只是品質天花板參考。
- **FLUX.1-schnell 排除**:純背景路線保留 SDXL 與 Klein 兩個候選已足夠跑完 §4.7;
  日後要補只是再下一個檔案(all-in-one fp8 17.24 GB,或 GGUF Q6_K 9.83 GB + 需裝 `ComfyUI-GGUF`)。
- **Z-Image Turbo 保留**:賭文字渲染可能優於預期。若成立,§4.3 的含文字路線就能一次生成、
  省掉圖片與文字分開產再合成的整段流程——這個上檔空間值得花一次測試成本。

實際下載(全部 sha256 驗證通過):

| 檔案 | 大小 | 位置 | 來源 repo |
|---|---|---|---|
| `Juggernaut-XL_v9.safetensors` | 7.11 GB | `models/checkpoints/` | `RunDiffusion/Juggernaut-XL-v9` |
| `flux-2-klein-4b.safetensors` | 7.75 GB | `models/diffusion_models/` | `Comfy-Org/vae-text-encorder-for-flux-klein-4b` |
| `flux2-vae.safetensors` | 0.34 GB | `models/vae/` | 同上 |
| `qwen_3_4b.safetensors` | 8.04 GB | `models/text_encoders/` | 同上 |
| `z_image_turbo_int8_convrot.safetensors` | 6.20 GB | `models/diffusion_models/` | `Comfy-Org/z_image_turbo` |
| `z_image_ae.safetensors` | 0.34 GB | `models/vae/` | 同上 |
| **合計** | **29.78 GB** | | 下載耗時 38.7 分鐘,均速約 12.5 MB/s |

重跑用腳本:`ComfyUI/download-models.py`(已存在的檔案會 SKIP,中斷後直接重跑即可)。

**三個下載時才會發現的事**:

1. **Klein 4B 與 Z-Image Turbo 共用同一顆 text encoder**。兩個 repo 的 `qwen_3_4b.safetensors`
   sha256 都是 `6c671498…edfc5a`、大小都是 8044982048 bytes,是同一個檔案。只下一次,省 8.04 GB。

2. **Klein 的 text encoder 不能從 BFL 原 repo 拿**。`black-forest-labs/FLUX.2-klein-4B` 的
   `text_encoder/` 是 diffusers 兩片分片格式,ComfyUI 的 `CLIPLoader` 只吃單檔 safetensors。
   要用 Comfy-Org 重打包的 `vae-text-encorder-for-flux-klein-4b`。

3. **Z-Image 取 int8,不取 nvfp4 也不取 bf16**——這關係到要測的東西本身:
   - bf16 單檔 12.31 GB,而本機 VRAM 12227 MiB。模型幾乎填滿,activation 無處可放,必須 offload,
     §4.4 量到的耗時會失去意義。
   - nvfp4(4.51 GB)最快,sm_120 有原生 FP4 tensor core、cu130 後 `scaled_mm_nvfp4` kernel 也啟用了。
     **但 4-bit 量化傷害最大的正是文字字形細節**,而測 Z-Image 的唯一理由就是文字。用 fp4 測會分不清
     字爛是模型不行還是量化砍掉的。
   - int8(6.20 GB)兩邊都不犧牲:塞得舒服,`int8_linear` / `quantize_int8_rowwise` 也在啟用的 CUDA 後端裡。
   - 若 int8 測出來卡在 §4.3 的 1/4 門檻邊緣、難以判定,那時再下 bf16 做仲裁才有意義。

**§3 表格的兩處低估**(實際查檔案大小後才看得出來):

- **FLUX.2 Klein 4B 標「舒適」只算了 transformer**。本體 7.75 GB 沒錯,但它自帶 8.05 GB 的 text encoder,
  相加 16 GB 已超過 12 GB VRAM。ComfyUI 會 encode 完換出 encoder 再載入 transformer,能跑,
  但每次換 prompt 都要搬一次記憶體。「舒適」對磁碟成立,對執行不完全成立。
- **Z-Image Turbo 標「12–16 GB,你在下緣」實際更緊**。bf16 本體 12.31 GB 單獨一項就吃滿 VRAM,
  再加 8.04 GB encoder,bf16 路線在這張卡上實質不可行。

**SDXL checkpoint 的選擇理由是格式,不是風格**:Juggernaut XL v9 有 ComfyUI 可直接載入的 7.11 GB 單檔;
另一個考慮過的 DreamShaper XL 1.0 在 HF 上只有 diffusers 分片格式,要先自己轉檔——用需要手工轉檔的模型
當基準線,會在 §4 引入與模型能力無關的變因。

⚠️ **已知風險**:Juggernaut v9 的完整檔名是 `Juggernaut-XL_v9_RunDiffusionPhoto_v2`,偏寫實人像,
撞上 §4.7 規則 2(8 張中 ≥ 2 張可辨識人臉即淘汰)的機率高於偏插畫風的選項。
若真被淘汰,替代方案是 `Lykon/dreamshaper-xl-v2-turbo` 等有單檔版的社群 checkpoint,再下一個約 7 GB 檔案。

**載入器驗證**(ComfyUI `/object_info` 實測,確認不只是檔案存在):

| 載入器 | 可見選項 |
|---|---|
| `CheckpointLoaderSimple` | `Juggernaut-XL_v9.safetensors` |
| `UNETLoader` | `flux-2-klein-4b.safetensors`、`z_image_turbo_int8_convrot.safetensors` |
| `VAELoader` | `flux2-vae.safetensors`、`z_image_ae.safetensors` |
| `CLIPLoader` | `qwen_3_4b.safetensors` |

`CLIPLoader` 的 `type` 參數該選什麼,依各模型的官方 workflow 樣板為準
(ComfyUI 已內建 `comfyui-workflow-templates`,`type` 清單含 `flux2`、`qwen_image` 等),不要用猜的。

---

## 4. 步驟 2:選型測試協定(本文件的重點)

### 4.1 為什麼需要協定

隨手「每個模型都試試看哪個好」會得到一團無法比較的結果:你會不自覺地為喜歡的模型多調幾次
prompt,最後比的是**你在哪個模型上花了比較多時間**,不是模型本身。而且「這是最新的模型」
這個認知會直接污染主觀評分。

協定要做三件事:**控制變因、分開評兩種不同的品質、把主觀判斷延後到最後**。

### 4.2 控制變因

| 變因 | 固定為 |
|---|---|
| Prompt | seeder 計畫 §6.5 的模板 **1(霓虹舞台燈光)與 6(自然元素)**,兩種調性各測 |
| Seed | 固定 8 個(如 1001–1008),**所有模型用同一組** |
| 解析度 | 1536 × 864(16:9) |
| 步數 / CFG | 各模型用其**官方建議值**(schnell 4 步、SDXL 25–30 步),不要強行統一 |
| 取樣器 | 各模型官方建議 |

**步數不統一是刻意的**:強迫 schnell 跑 30 步只是浪費,強迫 SDXL 跑 4 步則不公平。
要比的是「各自在最佳狀態下的產出」,而時間成本會在 §4.4 被算進去。

### 4.3 兩條路線分開測

生圖路線尚未定案(seeder 計畫 §6.6),兩條路線對模型的要求不同,**必須分開測、分開結論**:

- **純背景路線**:用 §6.5 的模板 1 與 6 + 共用負面提示
- **含文字路線**:用 §6.7 的 B1 / B2 / B3,`{ARTIST}` 代入 5 個測試樂團
  (2 純中文名、2 純英文名、1 中英混合)

含文字路線有一條**硬性門檻**(seeder 計畫 §6.6 已定):
中文名的字形正確率若低於 **1/4**,判定該路線不可行,不必再比美感。

### 4.4 評分維度

每個模型 × 每組 prompt 產 8 張,填下表:

| 維度 | 怎麼量 | 為什麼重要 |
|---|---|---|
| **可用率** | 8 張裡「直接可用」幾張(0–8) | **最重要的指標**,見下方說明 |
| **單張最佳** | 8 張裡最好那張的主觀分(1–5) | 天花板 |
| **風格一致性** | 同 prompt 換 seed,8 張看起來像同一套系統嗎(1–5) | 40 張要能並排在首頁而不突兀 |
| **留白品質** | 有沒有可疊字的低對比區域(1–5) | 輪播需要(seeder 計畫 §6.5) |
| **單張耗時** | 秒 | 決定重試的心理成本 |
| **版權風險** | 出現可辨識人臉的張數 | 見 §6.4 紅線 |

**關於「可用率」為什麼比「單張最佳」重要**——這是最容易搞錯的地方:

> 模型 A:8 張裡 1 張驚豔(5 分),其餘 7 張不能用
> 模型 B:8 張裡 5 張堪用(4 分),沒有特別驚豔的
>
> 看「單張最佳」是 A 贏。但你要的是 **40 張風格一致的圖**:
> A 需要跑 320 張才湊得到 40 張可用,B 只需要 64 張。
> **真實成本 = 單張耗時 ÷ 可用率**,而不是單張品質。

實際算法:`每張可用圖的成本(秒) = 單張耗時 × 8 ÷ 可用率`。把這個數字填進決策表。

### 4.5 盲測(降低新模型偏誤)

八張一組產完後,把檔名改成隨機編號、混在一起再評分,**評完才對回哪張是哪個模型**。
如果懶得做完整盲測,至少做到:**先看圖再看檔名**。

### 4.6 記錄表模板

> ✅ **2026-08-22:實測值(RTX 5070,1536×864)。這張表原本是空的。**
>
> | 後端 | 步數 | 首張(含模型載入) | **穩態單張** | 1 小時可產出 |
> |---|---|---|---|---|
> | Z-Image Turbo(int8) | 8 | 13.4s | **7.8s** | ~460 張 |
> | SDXL(Juggernaut XL v9) | 30 | — | **12.0s** | ~300 張 |
> | FLUX.2 Klein 4B | 20 | 44.6s | **33.2s** | ~108 張 |
>
> **Klein 慢 4 倍是結構性的**:7.75 GB unet + 8.05 GB text encoder > 12 GB VRAM,
> 而批次每張都換場景槽位 → 每張都要重新 encode、每張都付一次搬運成本。
> 這正是 §3.1 已經預見的「『舒適』對磁碟成立,對執行不完全成立」。
>
> ⚠️ **量測時的陷阱**:ComfyUI 會快取節點輸出。相同 style + seed + prompt 重跑會直接
> 回傳上次的結果,耗時記成 **0.7 秒**——不報錯,但統計全毀。
> `pnpm batch` 已改為自動接續 seed 來避開;**手動在 ComfyUI 介面量測時要自己注意**。
>
> 可用率與「每張可用圖成本」由 `pnpm batch --stats` 累積計算,不需要手抄這張表。

複製這張表,每個模型一列(純背景路線):

| 模型 | 可用率 /8 | 單張最佳 /5 | 一致性 /5 | 留白 /5 | 單張秒數 | **每張可用圖成本(秒)** | 人臉張數 | 備註 |
|---|---|---|---|---|---|---|---|---|
| SDXL (checkpoint 名) | | | | | | | | |
| FLUX.2 Klein 4B | | | | | | | | |
| FLUX.1-schnell | | | | | | | | |
| Z-Image Turbo | | | | | | | | |

含文字路線另一張(只在 §4.3 門檻通過時才需要填):

| 模型 | 中文字正確 /8 | 英文字正確 /8 | 排版自然度 /5 | 單張秒數 | 結論 |
|---|---|---|---|---|---|
| | | | | | |

### 4.7 決策規則

依序套用,**不要跳過**:

1. **含文字路線先過門檻**:中文正確率 < 1/4 → 淘汰該路線,只在純背景候選中選
2. **版權淘汰**:8 張中出現 ≥ 2 張可辨識人臉的模型,直接淘汰(prompt 調不動的話不值得纏鬥)
3. **一致性門檻**:一致性 < 3 分淘汰——40 張擺在一起會像拼貼
4. **在剩下的候選中,選「每張可用圖成本」最低者**
5. 若前兩名成本差距 < 20%,選**授權較乾淨**的(Apache 2.0 優先)

---

## 5. 步驟 3–5:批次生成、挑圖、轉檔

### 5.1 批次生成

選定模型後,依 seeder 計畫 §6.5 的**八組風格模板**分配給各樂團(記錄對應關係,日後補圖要用)。
每個樂團跑 4 個候選(遞增 seed),40 個樂團 = 160 張,掛 ComfyUI 佇列跑整晚。

**風格模板與樂團的對應要當場記下來**,寫進 registry 的註解或另一份對照表。
日後補一個新樂團時,你需要知道它該用哪一組才能維持整體調性。

### 5.2 挑圖與版權檢查

每個樂團從 4 張裡挑 1 張。挑的時候**逐張過 seeder 計畫 §6.4 的紅線**:

- [ ] 沒有可辨識的人臉(剪影、背影且認不出特定人 = 可以)
- [ ] 沒有任何真實藝人/樂團的 logo 或標準字
- [ ] 不像在模仿某張真實官方海報的構圖
- [ ] (純背景路線)沒有任何文字或類文字符號

有疑慮就丟掉重跑,不要為了省時間留下模稜兩可的圖。

### 5.3 轉檔

> ❌ **2026-08-22:本節的 `magick mogrify` 指令在本機跑不起來**——ImageMagick 不在 PATH。
>
> 改用 `sharp`(Node,npm 安裝的原生二進位,不需要改系統環境變數),
> 已整合進 `pnpm compose`:截圖 → 長邊縮 1200 → WebP q80,與本節規格相同。

規格(seeder 計畫 §6.3):長邊 1200、WebP、quality 80,約 100–150 KB。

```bash
magick mogrify -path ./out -resize 1200x -quality 80 -format webp ./picked/*.png
```

轉完檢查單檔大小,異常大的通常是雜訊過多的圖,考慮換一張。

---

## 6. 步驟 6:交付

交付方式取決於當時進行到哪個里程碑:

| 情境 | 做法 |
|---|---|
| **seeder 計畫階段**(registry 硬編碼) | 檔案放 `frontend/public/posters/artists/{slug}.webp`,`posterRegistry.ts` 加一行,commit → CD 自動部署 |
| **海報動態管理里程碑之後** | 後台登入 → `/admin/artists` → 新增藝人 → 上傳 WebP,**不需要 commit、不需要部署** |

第二種情境下,本機的 `frontend/public/posters/` 會被加進 `.gitignore`
(見動態管理計畫 §4.6),那個目錄只是給本機開發預覽用的鏡像。

### 每次新增一個樂團的簡版流程(動態管理上線後)

1. 查對照表,決定這個樂團用哪一組風格模板
2. ComfyUI 跑 4 張候選(約 1 分鐘)
3. 挑 1 張,過 §5.2 版權檢查清單
4. `magick` 轉 WebP
5. 後台新增藝人 + 上傳
6. 等 ≤ 10 分鐘,seeder 下個 tick 就會開始為它產生活動

---

## 7. 補充:Ollama 在這個專案的位置

Ollama **不是生圖工具**(它的實驗性生圖功能只在 macOS 推出過,且已於 v0.32.6 暫時移除;
本機安裝的是 0.32.9,比移除的版本更新)。

但它在這個專案有一個真正合適的用途:**產生文案詞素池**。
seeder 計畫 §5.2 要求 800 筆彼此差異度高的中文描述、每筆至少 2 個「全庫唯一」的專有名詞
(專輯名、曲名、巡演主題),這正適合用本機 LLM 批次產出後人工篩選,再餵給 `DemoContentPool`。

那部分不在本手冊範圍,需要時另外開一份。
