# React Native Document Scanner Wrapper

[English](#english-version) | [한국어](#한국어-버전)

---

## 한국어 버전

React Native용 문서 스캐너 라이브러리입니다. [`react-native-document-scanner`](https://github.com/Michaelvilleneuve/react-native-document-scanner)를 래핑하여 iOS와 Android 모두에서 네이티브 문서 스캐너를 제공합니다.

> 네이티브 구현은 업스트림 라이브러리에 포함되어 있습니다 (iOS: Objective-C/OpenCV, Android: Kotlin/OpenCV). 이 패키지는 타입 안전한 래퍼, 선택적 크롭 에디터 헬퍼, 전체 화면 스캐너를 제공합니다.

## ✨ 전문가급 카메라 품질 (v3.2+)

**주요 업데이트:** 최신 `AVCapturePhotoOutput` API로 업그레이드되어 이미지 품질이 대폭 향상되었습니다!

### 🚀 새로운 기능:
- **최신 카메라 API** - 구형 `AVCaptureStillImageOutput` 대신 `AVCapturePhotoOutput` (iOS 10+) 사용
- **iPhone 네이티브 품질** - 기본 카메라 앱과 동일한 품질
- **컴퓨테이셔널 포토그래피** - 자동 HDR, Deep Fusion, Smart HDR 지원
- **12MP+ 해상도** - 최신 iPhone에서 전체 해상도 캡처 (iPhone 14 Pro+ 기준 최대 48MP)
- **최대 품질 우선순위** - iOS 13+ 품질 우선순위 활성화
- **95%+ JPEG 품질** - 품질 손실 방지를 위한 최소 압축 품질 강제

### 🎯 자동 최적화:
- **고해상도 캡처** - 전체 센서 해상도 활성화 (`AVCaptureSessionPresetHigh`)
- **최소 95% JPEG** - 압축으로 인한 품질 저하 방지
- **고급 기능**:
  - 더 선명한 이미지를 위한 비디오 안정화
  - 항상 선명한 캡처를 위한 연속 자동 초점
  - 자동 노출 및 화이트 밸런스
  - 어두운 환경에서 저조도 부스트
- **하드웨어 가속** - 효율적인 처리를 위한 CIContext

### ⚡ 완전 자동 설치:
yarn/npm으로 설치하기만 하면 됩니다 - **수동 설정 불필요!**
- Postinstall 스크립트가 자동으로 카메라 품질 패치
- 설치 중 iOS 최적화 파일 자동 복사
- `pod install` 후 즉시 사용 가능

## 설치 방법

### 1. 패키지 설치

```bash
yarn add react-native-rectangle-doc-scanner \
  github:Michaelvilleneuve/react-native-document-scanner \
  react-native-perspective-image-cropper
```

또는 npm 사용:

```bash
npm install react-native-rectangle-doc-scanner \
  github:Michaelvilleneuve/react-native-document-scanner \
  react-native-perspective-image-cropper
```

### 2. Peer Dependencies 설치

이 라이브러리는 다음 peer dependencies를 필요로 합니다:

```bash
yarn add react-native-fs \
  react-native-image-crop-picker \
  react-native-image-picker \
  react-native-svg \
  expo-modules-core
```

또는 npm 사용:

```bash
npm install react-native-fs \
  react-native-image-crop-picker \
  react-native-image-picker \
  react-native-svg \
  expo-modules-core
```

**선택사항 (이미지 회전 기능을 사용하려면):**

```bash
# 둘 중 하나 선택
yarn add expo-image-manipulator
# 또는
yarn add react-native-image-rotate
```

### 2-1. Babel 및 Reanimated 설정 (필요시)

프로젝트에 `babel.config.js` 파일이 있는 경우, 다음 플러그인이 필요할 수 있습니다:

```javascript
module.exports = {
  presets: ['module:@react-native/babel-preset'],
  plugins: [
    'react-native-reanimated/plugin' // 마지막에 위치해야 함
  ],
};
```

**필요한 경우 추가 패키지:**

```bash
yarn add react-native-reanimated
```

### 3. iOS 설정

```bash
cd ios && pod install && cd ..
```

**Info.plist에 카메라 권한 추가:**

`ios/YourApp/Info.plist` 파일에 다음 권한을 추가하세요:

```xml
<key>NSCameraUsageDescription</key>
<string>문서를 스캔하기 위해 카메라 접근이 필요합니다</string>
<key>NSPhotoLibraryUsageDescription</key>
<string>스캔한 문서를 저장하기 위해 사진 라이브러리 접근이 필요합니다</string>
<key>NSPhotoLibraryAddUsageDescription</key>
<string>스캔한 문서를 저장하기 위해 사진 라이브러리 접근이 필요합니다</string>
```

### 4. Android 설정

Android는 자동으로 네이티브 모듈을 링크합니다. 레거시 아키텍처를 사용하는 경우, `MainApplication.java`에서 `DocumentScannerPackage()`를 수동으로 등록해야 합니다.

**AndroidManifest.xml에 권한 추가:**

`android/app/src/main/AndroidManifest.xml` 파일에 다음 권한이 자동으로 포함됩니다:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />

<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
<uses-feature android:name="android.hardware.camera.flash" android:required="false" />
```

**Gradle 설정:**

라이브러리는 다음 최소 요구사항을 가지고 있습니다:
- `minSdkVersion`: 21
- `compileSdkVersion`: 33
- `targetSdkVersion`: 33
- Kotlin: 1.8.21
- Java: 17

이 설정은 자동으로 적용되지만, 프로젝트의 `android/build.gradle`에서 호환되는 버전을 사용하는지 확인하세요.

**프로젝트의 `android/build.gradle` 예시:**

```gradle
buildscript {
    ext {
        buildToolsVersion = "33.0.0"
        minSdkVersion = 21
        compileSdkVersion = 33
        targetSdkVersion = 33
        kotlinVersion = "1.8.21"
    }
    dependencies {
        classpath("com.android.tools.build:gradle:7.4.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}
```

**프로젝트의 `android/app/build.gradle` 예시:**

```gradle
android {
    compileSdkVersion rootProject.ext.compileSdkVersion

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }

    defaultConfig {
        minSdkVersion rootProject.ext.minSdkVersion
        targetSdkVersion rootProject.ext.targetSdkVersion
    }
}
```

### 5. 자동 품질 패치 (Postinstall)

이 라이브러리는 **postinstall 스크립트**를 통해 자동으로 카메라 품질을 최적화합니다:

```bash
# 패키지 설치 시 자동 실행됨
node scripts/postinstall.js
```

**postinstall이 하는 일:**
1. `react-native-document-scanner` 패키지를 찾습니다 (node_modules에서 자동 감지)
2. vendor 폴더의 최적화된 iOS 파일들을 복사합니다:
   - `IPDFCameraViewController.m/h` - AVCapturePhotoOutput 사용
   - `DocumentScannerView.m/h` - 고품질 설정
   - `RNPdfScannerManager.m/h` - 네이티브 브릿지
   - `ios.js`, `index.js` - JavaScript 인터페이스
3. 원본 파일은 `.original` 확장자로 백업됩니다

**수동으로 실행하려면:**

```bash
npm run postinstall
# 또는
node scripts/postinstall.js
```

**문제 해결:**
- postinstall이 실패하는 경우, `react-native-document-scanner`가 설치되어 있는지 확인하세요
- yarn workspaces나 monorepo를 사용하는 경우, 패키지 호이스팅으로 인해 경로가 다를 수 있습니다

### 6. 런타임 권한 요청

앱에서 런타임에 카메라 권한을 요청해야 합니다:

```typescript
import { PermissionsAndroid, Platform } from 'react-native';

async function requestCameraPermission() {
  if (Platform.OS === 'android') {
    try {
      const granted = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.CAMERA,
        {
          title: '카메라 권한',
          message: '문서를 스캔하기 위해 카메라 접근이 필요합니다',
          buttonNeutral: '나중에',
          buttonNegative: '거부',
          buttonPositive: '허용',
        }
      );
      return granted === PermissionsAndroid.RESULTS.GRANTED;
    } catch (err) {
      console.warn(err);
      return false;
    }
  }
  return true;
}
```

## 사용 방법

### 기본 사용 예제

```tsx
import React, { useRef } from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { DocScanner, type DocScannerHandle } from 'react-native-rectangle-doc-scanner';

export const ScanScreen = () => {
  const scannerRef = useRef<DocScannerHandle>(null);

  return (
    <View style={styles.container}>
      <DocScanner
        ref={scannerRef}
        overlayColor="rgba(0, 126, 244, 0.35)"
        autoCapture
        minStableFrames={6}
        onCapture={(result) => {
          console.log('문서 캡처됨:', result.path);
          console.log('크기:', result.width, 'x', result.height);
        }}
      >
        <View style={styles.overlay} pointerEvents="none">
          <Text style={styles.hint}>프레임 안에 문서를 정렬하세요</Text>
        </View>
      </DocScanner>

      <TouchableOpacity
        style={styles.captureButton}
        onPress={() => scannerRef.current?.capture()}
      >
        <Text style={styles.captureButtonText}>촬영</Text>
      </TouchableOpacity>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000'
  },
  overlay: {
    position: 'absolute',
    top: 60,
    alignSelf: 'center',
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 12,
    backgroundColor: 'rgba(0,0,0,0.5)',
  },
  hint: {
    color: '#fff',
    fontWeight: '600'
  },
  captureButton: {
    position: 'absolute',
    bottom: 40,
    alignSelf: 'center',
    width: 70,
    height: 70,
    borderRadius: 35,
    backgroundColor: '#fff',
    justifyContent: 'center',
    alignItems: 'center',
  },
  captureButtonText: {
    color: '#000',
    fontWeight: '600',
  },
});
```

## Props

`<DocScanner />` 컴포넌트는 다음 props를 지원합니다:

| Prop | 타입 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `overlayColor` | `string` | `#0b7ef4` | 네이티브 오버레이 색상 |
| `autoCapture` | `boolean` | `true` | 자동 캡처 활성화 (내부적으로 `manualOnly`로 매핑됨) |
| `minStableFrames` | `number` | `8` | 자동 캡처 전 필요한 안정적인 프레임 수 |
| `enableTorch` | `boolean` | `false` | 플래시 켜기/끄기 |
| `quality` | `number` | `90` | 이미지 품질 (0–100, 네이티브용으로 변환됨) |
| `useBase64` | `boolean` | `false` | 파일 URI 대신 base64로 반환 |
| `onCapture` | `(result) => void` | — | `{ path, quad: null, width, height }` 객체를 전달받음 |

### 수동 캡처

ref를 통해 `capture()` 메서드를 사용하여 수동으로 캡처할 수 있습니다. children을 사용하여 카메라 프리뷰 위에 커스텀 UI(버튼, 진행 표시기, 온보딩 팁 등)를 렌더링할 수 있습니다.

## 추가 API

### CropEditor

`react-native-perspective-image-cropper`를 래핑하여 수동으로 모서리를 조정할 수 있는 크롭 에디터를 제공합니다.

```tsx
import { CropEditor } from 'react-native-rectangle-doc-scanner';

<CropEditor
  imagePath={capturedImagePath}
  onCropComplete={(croppedPath) => {
    console.log('크롭된 이미지:', croppedPath);
  }}
  onCancel={() => {
    console.log('크롭 취소');
  }}
/>
```

### FullDocScanner

스캐너와 크롭 에디터를 단일 모달형 플로우로 제공합니다. `expo-image-manipulator` 또는 `react-native-image-rotate`가 설치되어 있으면, 확인 화면에서 90° 회전 버튼이 표시됩니다.

```tsx
import { FullDocScanner } from 'react-native-rectangle-doc-scanner';

<FullDocScanner
  onComplete={(result) => {
    console.log('완료:', result);
  }}
  onCancel={() => {
    console.log('취소');
  }}
/>
```

## 기술 스택

### iOS
- **언어**: Objective-C
- **카메라 API**: AVCapturePhotoOutput (iOS 10+)
- **이미지 처리**: OpenCV, CoreImage (CIContext)
- **최소 버전**: iOS 11.0

### Android
- **언어**: Kotlin
- **카메라**: CameraX 1.3.0, Camera2 API
- **이미지 처리**: OpenCV 4.9.0
- **ML Kit**: 문서 스캔 및 객체 감지
- **최소 SDK**: 21 (Android 5.0)
- **타겟 SDK**: 33 (Android 13)
- **Kotlin**: 1.8.21
- **Java**: 17

## 문제 해결

### iOS 빌드 오류

Pod 설치 후에도 빌드 오류가 발생하는 경우:

```bash
cd ios
rm -rf Pods Podfile.lock
pod cache clean --all
pod install
cd ..
```

### Android 빌드 오류

Gradle 빌드 오류가 발생하는 경우:

```bash
cd android
./gradlew clean
cd ..
```

### 권한 오류

카메라가 작동하지 않는 경우, 런타임 권한이 올바르게 요청되었는지 확인하세요. iOS의 경우 Info.plist에 권한 설명이 추가되어 있는지, Android의 경우 PermissionsAndroid로 권한을 요청했는지 확인하세요.

## 라이선스

MIT

---

## English Version

React Native-friendly wrapper around [`react-native-document-scanner`](https://github.com/Michaelvilleneuve/react-native-document-scanner). It exposes a declarative `<DocScanner />` component that renders the native document scanner on both iOS and Android while keeping the surface area small enough to plug into custom UIs.

> The native implementation lives inside the upstream library (Objective-C/OpenCV on iOS, Kotlin/OpenCV on Android). This package simply re-exports a type-safe wrapper, optional crop editor helpers, and a full-screen scanner flow.

## ✨ Professional Camera Quality (v3.2+)

**Major Update:** Upgraded to modern `AVCapturePhotoOutput` API for dramatically improved image quality!

### 🚀 What's New:
- **Modern Camera API** - Uses `AVCapturePhotoOutput` (iOS 10+) instead of deprecated `AVCaptureStillImageOutput`
- **iPhone Native Quality** - Same quality as the built-in Camera app
- **Computational Photography** - Automatic HDR, Deep Fusion, and Smart HDR support
- **12MP+ Resolution** - Full resolution capture on modern iPhones (up to 48MP on iPhone 14 Pro+)
- **Maximum Quality Priority** - iOS 13+ quality prioritization enabled
- **95%+ JPEG Quality** - Enforced minimum compression quality to prevent quality loss

### 🎯 Automatic Optimizations:
- **High-Resolution Capture** - Full sensor resolution enabled (`AVCaptureSessionPresetHigh`)
- **Minimum 95% JPEG** - Prevents quality degradation from compression
- **Advanced Features**:
  - Video stabilization for sharper images
  - Continuous autofocus for always-sharp captures
  - Auto exposure and white balance
  - Low-light boost in dark environments
- **Hardware-Accelerated** - CIContext for efficient processing

### ⚡ Fully Automatic Installation:
Just install with yarn/npm - **no manual configuration needed!**
- Postinstall script automatically patches camera quality
- Optimized iOS files copied during installation
- Works immediately after `pod install`

## Installation

### 1. Install the Package

```bash
yarn add react-native-rectangle-doc-scanner \
  github:Michaelvilleneuve/react-native-document-scanner \
  react-native-perspective-image-cropper
```

Or using npm:

```bash
npm install react-native-rectangle-doc-scanner \
  github:Michaelvilleneuve/react-native-document-scanner \
  react-native-perspective-image-cropper
```

### 2. Install Peer Dependencies

This library requires the following peer dependencies:

```bash
yarn add react-native-fs \
  react-native-image-crop-picker \
  react-native-image-picker \
  react-native-svg \
  expo-modules-core
```

Or using npm:

```bash
npm install react-native-fs \
  react-native-image-crop-picker \
  react-native-image-picker \
  react-native-svg \
  expo-modules-core
```

**Optional (for image rotation features):**

```bash
# Choose one
yarn add expo-image-manipulator
# or
yarn add react-native-image-rotate
```

### 2-1. Babel and Reanimated Setup (if needed)

If your project has a `babel.config.js` file, you may need the following plugins:

```javascript
module.exports = {
  presets: ['module:@react-native/babel-preset'],
  plugins: [
    'react-native-reanimated/plugin' // Must be listed last
  ],
};
```

**Install additional packages if needed:**

```bash
yarn add react-native-reanimated
```

### 3. iOS Setup

```bash
cd ios && pod install && cd ..
```

**Add Camera Permissions to Info.plist:**

Add the following permissions to your `ios/YourApp/Info.plist` file:

```xml
<key>NSCameraUsageDescription</key>
<string>We need camera access to scan documents</string>
<key>NSPhotoLibraryUsageDescription</key>
<string>We need photo library access to save scanned documents</string>
<key>NSPhotoLibraryAddUsageDescription</key>
<string>We need photo library access to save scanned documents</string>
```

### 4. Android Setup

Android automatically links the native module. If you manage packages manually (legacy architecture), register `DocumentScannerPackage()` in your `MainApplication.java`.

**Permissions are automatically included:**

The following permissions are automatically included in the library's `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />

<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />
<uses-feature android:name="android.hardware.camera.flash" android:required="false" />
```

**Gradle Configuration:**

The library has the following minimum requirements:
- `minSdkVersion`: 21
- `compileSdkVersion`: 33
- `targetSdkVersion`: 33
- Kotlin: 1.8.21
- Java: 17

These are automatically applied, but make sure your project's `android/build.gradle` uses compatible versions.

### 5. Request Runtime Permissions

You need to request camera permissions at runtime in your app:

```typescript
import { PermissionsAndroid, Platform } from 'react-native';

async function requestCameraPermission() {
  if (Platform.OS === 'android') {
    try {
      const granted = await PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.CAMERA,
        {
          title: 'Camera Permission',
          message: 'We need camera access to scan documents',
          buttonNeutral: 'Ask Me Later',
          buttonNegative: 'Cancel',
          buttonPositive: 'OK',
        }
      );
      return granted === PermissionsAndroid.RESULTS.GRANTED;
    } catch (err) {
      console.warn(err);
      return false;
    }
  }
  return true;
}
```

## Usage

### Basic Example

```tsx
import React, { useRef } from 'react';
import { StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { DocScanner, type DocScannerHandle } from 'react-native-rectangle-doc-scanner';

export const ScanScreen = () => {
  const scannerRef = useRef<DocScannerHandle>(null);

  return (
    <View style={styles.container}>
      <DocScanner
        ref={scannerRef}
        overlayColor="rgba(0, 126, 244, 0.35)"
        autoCapture
        minStableFrames={6}
        onCapture={(result) => {
          console.log('Captured document:', result.path);
          console.log('Dimensions:', result.width, 'x', result.height);
        }}
      >
        <View style={styles.overlay} pointerEvents="none">
          <Text style={styles.hint}>Align the document inside the frame</Text>
        </View>
      </DocScanner>

      <TouchableOpacity
        style={styles.captureButton}
        onPress={() => scannerRef.current?.capture()}
      >
        <Text style={styles.captureButtonText}>Capture</Text>
      </TouchableOpacity>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#000'
  },
  overlay: {
    position: 'absolute',
    top: 60,
    alignSelf: 'center',
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 12,
    backgroundColor: 'rgba(0,0,0,0.5)',
  },
  hint: {
    color: '#fff',
    fontWeight: '600'
  },
  captureButton: {
    position: 'absolute',
    bottom: 40,
    alignSelf: 'center',
    width: 70,
    height: 70,
    borderRadius: 35,
    backgroundColor: '#fff',
    justifyContent: 'center',
    alignItems: 'center',
  },
  captureButtonText: {
    color: '#000',
    fontWeight: '600',
  },
});
```

## Props

`<DocScanner />` passes through the important upstream props:

| Prop | Type | Default | Notes |
| --- | --- | --- | --- |
| `overlayColor` | `string` | `#0b7ef4` | Native overlay tint. |
| `autoCapture` | `boolean` | `true` | Maps to `manualOnly` internally. |
| `minStableFrames` | `number` | `8` | Detection count before auto capture. |
| `enableTorch` | `boolean` | `false` | Toggle device torch. |
| `quality` | `number` | `90` | 0–100 (converted for native). |
| `useBase64` | `boolean` | `false` | Return base64 payloads instead of file URIs. |
| `onCapture` | `(result) => void` | — | Receives `{ path, quad: null, width, height }`. |

### Manual Capture

Manual capture exposes an imperative `capture()` method via `ref`. Children render on top of the camera preview so you can build your own buttons, progress indicators, or onboarding tips.

## Convenience APIs

### CropEditor

Wraps `react-native-perspective-image-cropper` for manual corner adjustment.

```tsx
import { CropEditor } from 'react-native-rectangle-doc-scanner';

<CropEditor
  imagePath={capturedImagePath}
  onCropComplete={(croppedPath) => {
    console.log('Cropped image:', croppedPath);
  }}
  onCancel={() => {
    console.log('Crop cancelled');
  }}
/>
```

### FullDocScanner

Puts the scanner and crop editor into a single modal-like flow. If the host app links either `expo-image-manipulator` or `react-native-image-rotate`, the confirmation screen exposes 90° rotation buttons; otherwise rotation controls remain hidden.

```tsx
import { FullDocScanner } from 'react-native-rectangle-doc-scanner';

<FullDocScanner
  onComplete={(result) => {
    console.log('Completed:', result);
  }}
  onCancel={() => {
    console.log('Cancelled');
  }}
/>
```

## Tech Stack

### iOS
- **Language**: Objective-C
- **Camera API**: AVCapturePhotoOutput (iOS 10+)
- **Image Processing**: OpenCV, CoreImage (CIContext)
- **Minimum Version**: iOS 11.0

### Android
- **Language**: Kotlin
- **Camera**: CameraX 1.3.0, Camera2 API
- **Image Processing**: OpenCV 4.9.0
- **ML Kit**: Document scanning and object detection
- **Minimum SDK**: 21 (Android 5.0)
- **Target SDK**: 33 (Android 13)
- **Kotlin**: 1.8.21
- **Java**: 17

## Troubleshooting

### iOS Build Errors

If you encounter build errors after pod install:

```bash
cd ios
rm -rf Pods Podfile.lock
pod cache clean --all
pod install
cd ..
```

### Android Build Errors

If you encounter Gradle build errors:

```bash
cd android
./gradlew clean
cd ..
```

### Permission Errors

If the camera is not working, make sure you have requested runtime permissions correctly. For iOS, check that permission descriptions are added to Info.plist. For Android, ensure you've requested permissions using PermissionsAndroid.

## License

MIT
