# Setup Instructions

## 업데이트 내용 (v3.41.0)

이제 `FullDocScanner`가 `react-native-image-crop-picker`를 사용해서 크롭합니다!

### 주요 변경사항

1. **수동 촬영 후 자동으로 cropper 실행**
   - 수동/자동 촬영 후 바로 `react-native-image-crop-picker`의 cropper가 실행됩니다
   - 사용자가 직접 이미지를 자를 수 있습니다

2. **갤러리 버튼 추가**
   - 촬영 버튼 옆에 갤러리 버튼이 추가되었습니다 (📁)
   - 갤러리에서 이미지를 선택하고 바로 크롭할 수 있습니다

3. **간소화된 API**
   - `CropEditor` 화면 제거
   - `onResult` 콜백이 이제 `{ path, base64 }` 형태로 결과를 반환합니다

## 빌드 방법

```bash
# 1. 패키지 디렉토리로 이동
cd /Users/tt-mac-03/Documents/workspace/react-native-doc-scanner/react-native-rectangle-doc-scanner

# 2. TypeScript 빌드
npm run build
# 또는
yarn build
```

## tdb 프로젝트에 설치

### 1. 필수 peer dependencies 설치

tdb 프로젝트에서 다음 패키지들을 설치해야 합니다:

```bash
cd /Users/tt-mac-03/Documents/workspace/tdb

npm install react-native-image-picker react-native-image-crop-picker
# 또는
yarn add react-native-image-picker react-native-image-crop-picker
```

### 2. iOS 설정 (react-native-image-picker)

`tdb/ios/Podfile`에 다음 권한 추가:

```ruby
post_install do |installer|
  installer.pods_project.targets.each do |target|
    target.build_configurations.each do |config|
      config.build_settings['GCC_PREPROCESSOR_DEFINITIONS'] ||= ['$(inherited)']
    end
  end
end
```

`tdb/ios/tdb/Info.plist`에 권한 추가:

```xml
<key>NSPhotoLibraryUsageDescription</key>
<string>문서를 선택하기 위해 갤러리 접근이 필요합니다</string>
<key>NSCameraUsageDescription</key>
<string>문서를 촬영하기 위해 카메라 접근이 필요합니다</string>
```

### 3. iOS pod install

```bash
cd /Users/tt-mac-03/Documents/workspace/tdb/ios
pod install
```

### 4. 패키지 재설치

```bash
cd /Users/tt-mac-03/Documents/workspace/tdb

# 기존 설치 제거
rm -rf node_modules/react-native-rectangle-doc-scanner

# 재설치
npm install
# 또는
yarn install
```

## 사용 방법

```typescript
import { FullDocScanner } from 'react-native-rectangle-doc-scanner';
import type { FullDocScannerResult } from 'react-native-rectangle-doc-scanner';

function MyComponent() {
  const handleResult = (result: FullDocScannerResult) => {
    console.log('Cropped image path:', result.path);
    console.log('Base64:', result.base64);
  };

  return (
    <FullDocScanner
      onResult={handleResult}
      onClose={() => console.log('Closed')}
      enableGallery={true}
      strings={{
        captureHint: '문서를 프레임 안에 맞춰주세요',
        manualHint: '아래 버튼을 눌러 촬영하세요',
        cancel: '취소',
        processing: '처리 중...',
        galleryButton: '갤러리',
      }}
    />
  );
}
```

> 참고: 최종 확인 화면의 회전 버튼은 프로젝트에 `expo-image-manipulator` 또는 `react-native-image-rotate` 네이티브 모듈이 연결되어 있을 때만 노출됩니다. 둘 중 하나라도 없으면 버튼이 자동으로 숨겨지고 원본 각도로 결과가 반환됩니다.

## API 변경사항

### FullDocScannerResult (변경됨)

**이전:**
```typescript
{
  original: CapturedDocument;
  rectangle: Rectangle | null;
  base64: string;
}
```

**현재:**
```typescript
{
  path: string;              // 크롭된 이미지 파일 경로
  base64?: string;           // Base64 인코딩된 이미지
  original?: CapturedDocument; // 원본 문서 정보 (옵션)
}
```

### FullDocScannerStrings (변경됨)

**제거된 필드:**
- `confirm` (cropper에서 사용)
- `retake` (cropper에서 사용)
- `cropTitle` (cropper에서 사용)

**추가된 필드:**
- `galleryButton` (갤러리 버튼 레이블)

### Props (추가됨)

- `enableGallery?: boolean` - 갤러리 버튼 표시 여부 (기본값: `true`)
- `cropWidth?: number` - 크롭 너비 (기본값: `1200`)
- `cropHeight?: number` - 크롭 높이 (기본값: `1600`)

## 문제 해결

### "Cannot find module 'react-native-image-picker'"

peer dependency를 설치하지 않았습니다:
```bash
npm install react-native-image-picker react-native-image-crop-picker
```

### iOS 빌드 에러

1. pod install 실행:
```bash
cd ios && pod install
```

2. Info.plist에 권한 추가 확인

### TypeScript 타입 에러

패키지를 다시 빌드하고 재설치:
```bash
cd /Users/tt-mac-03/Documents/workspace/react-native-doc-scanner/react-native-rectangle-doc-scanner
npm run build

cd /Users/tt-mac-03/Documents/workspace/tdb
rm -rf node_modules/react-native-rectangle-doc-scanner
npm install
```
