# 内置 LUT

共 10 项，默认选择 Fuji C200。升级时按精确内容摘要移除旧「示例调色」与「原色」条目，并清理对应缓存；保留其他导入项与有效选择，旧选择已移除时回到默认项。新增资源按内容摘要去重。

| 名称 | 网格 | 随包文件 | 原始文件／来源 |
| --- | --- | --- | --- |
| Fuji C200（默认） | 25³ | `FJC200.CUB` | `Fuji C200.cube` |
| Fuji Pro 400H | 25³ | `FJ400H.CUB` | `Fuji Pro 400H.cube` |
| Fuji Provia 100F | 25³ | `FJPROV.CUB` | `Fuji Provia 100F.cube` |
| Ilford Delta 400 | 25³ | `ILDEL400.CUB` | `Ilford Delta 400.cube` |
| Kodak Portra400 | 25³ | `KDPOR400.CUB` | `Kodak Portra400.cube` |
| Kodak Ultramax400 | 25³ | `KDULT400.CUB` | `Kodak Ultramax400.cube` |
| Kodak5207 | 25³ | `KD5207.CUB` | `Kodak5207.cube` |
| Ricoh GR3X NEG | 33³ | `GR3XNEG.CUB` | `Ricoh_GR3X_NEG.cube` |
| Ricoh GR3X POSI | 33³ | `GR3XPOS.CUB` | `Ricoh_GR3X_POSI.cube` |
| Ricoh GR4 CinemaGreen | 33³ | `GR4CINE.CUB` | `Ricoh_GR4_CinemaGreen.cube` |

指定文件来自维护者本机素材库 `assets/luts/collected/预设/`（工作区相对路径）。随包副本仅改为 Sony SD 卡兼容的 8.3 文件名，内容逐字保留，不重采样、不改标题或色域。前七个文件的内部 TITLE 均为 `ORI`，界面名称由目录清单单独提供。Ricoh 两个 GR3X 文件标题标为 sRGB，GR4 CinemaGreen 标为 Rec.709；保留原表语义。

内置表与用户导入表均按完整网格计算。自定义独立 CUBE 支持 2–65³，放入 SD 卡根目录 `LUTS/` 后从应用导入。近似取景参数仍由现有拟合器按需生成。

资源清单位于 `android-assets/CATALOG.JSN`；构建检查校验 SHA-256、网格阶数、数据行数与唯一默认项。单独克隆本应用即可构建，无需访问本机素材库。来源与许可记录见 [ASSET_LICENSES.md](../ASSET_LICENSES.md)。
