# Live world pipeline (phases 1 and 2, phase 3 in progress)

Status: phases 1 and 2 of `LIVE_WORLD_PLAN.md` are implemented in the `world/` package. The old record-then-process
scan flow (`ScanRecorder`, `Sfm`, `ReconPipeline`, `ReconService`, TSDF, `.glb` export) was removed.

## Screens
- `WorldListScreen` (home): saved worlds, New world, Open, Rename, Delete. "Store (Wi-Fi)" opens the old shop-matching screen.
- `LiveScanScreen`: black screen, the world drawn live, small camera view top-right. Start / Stop and save.
  Rotates freely. "View" toggles between a camera view and a from-behind view with a camera frustum.
- `WorldViewScreen`: orbit viewer (drag to look, pinch to zoom) for a saved world.

## Live pipeline
1. `LiveCameraPreview` binds CameraX Preview + ImageAnalysis (640x480, RGBA). `LiveAnalyzer` hands frames to `LiveSlam`.
2. `LiveSlam` (analysis thread): ORB features; two-view initialisation (essential matrix); PnP + RANSAC tracking against
   the last keyframes' map points; keyframes triangulate new sparse points. Scale is fixed so the median initial
   point is 2.5 m away. Gravity (IMU) defines "up" for the world frame (x right, y down, z forward at the start).
3. Depth worker (second thread): on each keyframe saves a JPEG + pose, runs `DepthEstimator` (MiDaS-small TFLite),
   fits it to the sparse points (1/z = a*d + b) and adds coloured points to `PointCloud` (4 cm voxel dedupe).
   Without `depth.tflite`, the sparse points are shown instead.
4. `CloudRenderer` projects the cloud on a plain Canvas (colour buckets, up to 60k points per frame). Used for the
   Low tier, for worlds without a depth model, and for point-only worlds from phase 1.
5. Autosave (`WorldSession`): `points.bin` is appended every 8 s, plus changed TSDF blocks (`chunks/`), `poses.jsonl`,
   `keyframes/`, `meta.json`; `thumbnail.jpg` is written on Stop.

## Live mesh (phase 2)
6. **Fusion** (`ChunkedTsdf.integrateFrame`, depth worker thread): every depth sample that passes an edge check
   (neighbouring samples within 10 % depth, range <= 6 m) becomes a ray. Voxels within +-(3 voxels + 2 % of range)
   of the surface get a truncated signed distance, a weight (1/(1+0.2 r), capped at 40) and a colour. The grid is
   sparse: 16x16x16-voxel blocks in a hash map, created on first touch.
7. **Meshing** (`MeshWorker`, own thread, every 100 ms): dirty blocks nearest the camera are re-meshed with naive
   surface nets inside a time slice (`Tier.meshBudgetMs`). A voxel needs weight >= 1 (about two hits). A block owns
   the edges that start at its voxels; neighbours of a changed boundary voxel are marked dirty too, so seams stay closed.
   Output per block: interleaved vertices (float3 position, snorm8 normal, unorm8 colour = 20 bytes) + int indices.
8. **Rendering** (`MeshGlView` / `MeshRenderer`, OpenGL ES 3.0): one VAO per block, depth test, two-sided light, vertex
   colours. `MeshStore` hands finished blocks to the GL thread and keeps the latest copy so a lost GL context can re-upload.
9. **Persistence and memory**: changed blocks are written to `chunks/<bx>_<by>_<bz>.blk` on each autosave
   (deflate; sdf int16, weight uint8 = w*6, colour 3 bytes). RAM is capped per tier (`maxBlocksInRam`, about 48 KB per
   block): the least recently touched saved blocks are evicted and reload from disk when touched again.

## Device tiers
`TierBenchmark` times ORB on a synthetic 640x480 image, one depth-model run, and reads the heap class; the result is
cached in SharedPreferences. The list screen's **Quality** dialog can force High/Mid/Low or re-run the benchmark.

| Tier | Voxel | Depth interval | Sample stride | Live view | Blocks in RAM |
|---|---|---|---|---|---|
| High | 4 cm | 200 ms | 4 px | live textured mesh | 2000 |
| Mid | 7 cm | 400 ms | 6 px | live coarse mesh | 1800 |
| Low | 11 cm | 1000 ms | 8 px | live points; mesh built on open | 1500 |

Benchmark thresholds (High: heap >= 384 MB, ORB <= 25 ms, depth <= 100 ms; Mid: 256 MB, 45 ms, 300 ms) are guesses and
need tuning on real phones.

## Saved world layout
```
filesDir/worlds/<id>/
  meta.json     name, times, point and keyframe counts, align_slam_to_world (3x3, row-major)
  points.bin    16-byte records: x, y, z (float32 LE), ARGB (int32 LE), gravity-aligned world frame
  chunks/       one .blk per 16^3-voxel TSDF block, same gravity-aligned frame; meta.json has voxel size and tier
  poses.jsonl   one line per keyframe: id, rot_deg, R (world->camera, SLAM frame), t
  keyframes/    00000.jpg ...
  thumbnail.jpg
```

## Needs
`app/src/main/assets/models/depth.tflite` (MiDaS-small, float32, 256x256 NHWC). Input size/layout are read from the model;
ImageNet mean/std normalisation is in `DepthEstimator.MEAN/STD`.

## Viewer
`WorldViewScreen` rebuilds the mesh from `chunks/` when a world is opened (streamed block by block, so large worlds
do not need to fit in RAM). Worlds without chunks (phase 1, or no depth model) show their points.

## Resume and extend (phase 3, step 1)
`WorldListScreen` -> **Resume** opens `LiveScanScreen` with the saved world; **Continue** starts it.
1. `ResumeData.read` loads `poses.jsonl`, the segments and intrinsics from `meta.json`, and the tier whose voxel size
   matches the saved blocks (a resumed world keeps its voxel size, whatever the Quality setting says).
2. `points.bin` is loaded into an indexed `PointCloud`, so new points keep deduping and only new ones are appended.
3. `LiveSlam.loadSaved` re-detects ORB features on the saved keyframe JPEGs (at most 400, evenly spaced) and rebuilds a
   sparse map by matching neighbouring keyframes and triangulating with their saved poses. Features are pinned in memory.
4. The saved mesh streams from `chunks/` into the live view (`publishSavedMesh`); new data fuses into the same blocks.
5. State `RELOCALIZING`: every camera frame is matched against 12 saved keyframes (the window cycles through all),
   then PnP + RANSAC (>= 35 inliers and >= 35 % of candidates). On success tracking continues **in the saved slam
   frame**, so new data lines up without any extra transform.
6. Fallback **Place by hand**: top-down map of the saved points; tap where you stand, set the facing direction.
   A fresh tracking session starts there (`placementSegment`), at the median saved camera height. The scale of a fresh
   session is a guess (median first point 2.5 m), so seams with old data may be off.
7. If tracking is lost later in a resumed world, the state returns to `RELOCALIZING` instead of `LOST`.

**Segments.** A world can have several slam frames (the first scan, plus one per "place by hand"). `meta.json` has
`segments: [{align, offset}]` (world = align * slam + offset); every line of `poses.jsonl` has `seg`. Worlds saved
before this have no `segments`: `align_slam_to_world` becomes segment 0. `intrinsics` [fx, fy, cx, cy] is saved too.

## Not done yet (phase 3)
Loop closure / drift correction (step 2: per-keyframe depth maps will be saved for re-fusing), texture baking and
`.glb` export (step 3). Not tested on a device: mesh quality, frame budget, tier thresholds, relocalization.
