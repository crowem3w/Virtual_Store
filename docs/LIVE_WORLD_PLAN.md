# Live World: Real-Time Camera-to-3D Plan

## 1. Goal

The user opens the app to a black screen. A small camera preview sits in the top-right corner. As the phone moves, the app turns what the camera sees into a 3D textured mesh in real time, like a world being drawn in front of you. The world is saved continuously, so it becomes a simulation of the real environment that can be reopened, extended and walked through.

## 2. Decisions

| Topic | Decision |
|---|---|
| Reconstruction | Camera-only: OpenCV tracking + TFLite monocular depth. No ARCore. |
| Visual style | Textured mesh. Vertex colors during live scanning, baked texture atlas on finish. |
| Devices | Any Android 11+ phone (minSdk 30), with performance tiers. |
| Saved worlds | Separate world-list screen inside the app. |
| Space size | Large multi-room spaces. |
| Depth model | Supplied by the project owner as `app/src/main/assets/models/depth.tflite`. |

## 3. Screens

### 3.1 World list (home)
- Grid or list of saved worlds: thumbnail, name, size, date.
- Actions: New world, Resume/extend, Open in viewer, Rename, Delete.

### 3.2 Live scan
- Black background, 3D world rendered fullscreen.
- Camera preview, top-right corner.
- Overlay: tracking quality indicator, triangle/chunk count, Pause and Stop.
- Hints: "Move slower", "Point at textured surfaces", "Tracking lost, return to a seen area".

### 3.3 World viewer
- Walk-through of a saved world using the existing `StoreScreen` and joystick.
- Manual scale and floor adjustment.

## 4. Pipeline

1. **Capture**: CameraX at low resolution with gyro and gravity sensors. Keyframes are chosen by motion and sharpness.
2. **Tracking**: incremental tracker replacing the batch `Sfm`. ORB features with optical flow, a local map, and gyro-assisted pose prediction. Includes tracking-lost handling and relocalization.
3. **Depth**: TFLite on keyframes only (about 2-5 per second), using GPU or NNAPI delegates when available. Each depth map is fitted to sparse map points for scale consistency.
4. **Fusion**: chunked, sparse TSDF voxel grid (4 m or 8 m chunks). Changed chunks are marked dirty.
5. **Meshing**: dirty chunks are re-meshed on a background thread and swapped into the renderer.
6. **Look**: vertex colors for the live view. A texture-baking pass on finish writes a real atlas from the keyframes.
7. **Persistence**: chunks and keyframes autosave to `worlds/<id>/`. Stop exports `model.glb`.

## 5. Large-space challenges

- **Drift**: no ARCore means error accumulates over long walks. Plan: loop closure with a bag-of-words place check, then pose-graph correction that moves the affected chunks. This is the largest risk.
- **Memory**: chunks stream in and out of RAM by distance. Only chunks near the camera are meshed live.
- **Scale**: monocular scale is uncertain. Keep the camera-height-above-floor heuristic and add a manual scale adjustment in the viewer.
- **Weak phones**: a startup benchmark selects tier settings (voxel size, depth rate, mesh detail). The lowest tier shows a point cloud and builds the mesh only after Stop.
- **Heat and battery**: adaptive frame rate and a warning on long sessions.

## 6. Device tiers

| Tier | Voxel size | Depth rate | Live view |
|---|---|---|---|
| High | 4 cm | 4-5 / s | Live textured mesh |
| Mid | 6-8 cm | 2-3 / s | Live coarse mesh |
| Low | 10-12 cm | 1 / s | Point cloud, mesh after Stop |

(Values are starting points to tune on real devices.)

## 7. Data layout

```
worlds/<id>/
  meta.json          name, created, updated, tier, scale, floor height
  keyframes/         JPEG keyframes + poses
  chunks/            serialized voxel chunks
  map.bin            sparse map points and pose graph
  model.glb          exported mesh (after Stop)
  thumbnail.jpg
```

## 8. Phases

### Phase 1: Foundation (implemented)
- New screen layout and navigation (world list, live scan).
- Incremental tracking and sparse map.
- Live point/voxel view.
- Autosave and world list.

### Phase 2: Live mesh (implemented, see PIPELINE.md)
- Chunked fusion and incremental meshing (16^3-voxel blocks; the 4 m / 8 m chunks of section 4 are a later streaming layer).
- Vertex-color live rendering (OpenGL ES 3.0).
- Device tier benchmark with manual override; TSDF chunks saved for Phase 3 resume.

### Phase 3: Finish
- Loop closure and pose-graph correction.
- Texture baking and `.glb` export.
- Resume and extend a saved world.

## 9. Risks

- Tracking fails on blank walls, in dim light and with fast motion; the UI must guide the user.
- Large-space quality depends on loop closure (Phase 3).
- CI only verifies that the code compiles. Tracking and mesh quality can only be validated on real phones.
- Depth models differ in input size, layout and normalization. `DepthEstimator` reads size and layout from the model, but mean/std may need tuning.

## 10. Open items

- Confirm the exact `depth.tflite` model and its normalization.
- Pick a target frame budget per tier after first on-device tests.
- Decide the maximum world size per saved world and the storage limit.
