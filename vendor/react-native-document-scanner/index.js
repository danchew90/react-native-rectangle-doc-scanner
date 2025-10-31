import React from 'react';
import {
  requireNativeComponent,
  NativeModules,
  View,
  Platform,
  PermissionsAndroid,
  DeviceEventEmitter,
  findNodeHandle,
} from 'react-native';
import PropTypes from 'prop-types';

const RNPdfScanner = requireNativeComponent('RNPdfScanner', PdfScanner);
const CameraManager = NativeModules.RNPdfScannerManager || {};

class PdfScanner extends React.Component {
  constructor(props) {
    super(props);
    this.state = {
      permissionsAuthorized: Platform.OS === 'ios',
    };
    this.eventsSubscribed = false;
    this.nativeRef = null;
    this.nativeTag = null;
  }

  onPermissionsDenied = () => {
    if (this.props.onPermissionsDenied) {
      this.props.onPermissionsDenied();
    }
  };

  componentDidMount() {
    this.subscribeNativeEvents();
    this.getAndroidPermissions();
  }

  componentWillUnmount() {
    this.unsubscribeNativeEvents();
  }

  UNSAFE_componentWillMount() {
    // Keep for backward compatibility in non-StrictMode React versions
    this.subscribeNativeEvents();
  }

  subscribeNativeEvents() {
    if (Platform.OS !== 'android') {
      return;
    }
    if (this.eventsSubscribed) {
      return;
    }
    const { onPictureTaken, onProcessing } = this.props;
    // React Native 0.76+ returns a subscription object from addListener
    this.pictureListener = DeviceEventEmitter.addListener('onPictureTaken', onPictureTaken);
    this.processingListener = DeviceEventEmitter.addListener('onProcessingChange', onProcessing);
    this.eventsSubscribed = true;
  }

  unsubscribeNativeEvents() {
    if (Platform.OS !== 'android') {
      return;
    }
    // React Native 0.76+ uses removeAllListeners instead of removeListener
    if (this.pictureListener) {
      this.pictureListener.remove();
      this.pictureListener = null;
    }
    if (this.processingListener) {
      this.processingListener.remove();
      this.processingListener = null;
    }
    this.eventsSubscribed = false;
  }

  async getAndroidPermissions() {
    if (Platform.OS !== 'android') {
      return;
    }
    try {
      const requestedPermissions = [
        PermissionsAndroid.PERMISSIONS.CAMERA,
      ];

      const isTiramisuOrNewer = Platform.Version >= 33;

      if (isTiramisuOrNewer) {
        // Android 13+: storage permissions split by media type. Request images access.
        if (PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES) {
          requestedPermissions.push(PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES);
        }
      } else {
        if (PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE) {
          requestedPermissions.push(PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE);
        }
        if (PermissionsAndroid.PERMISSIONS.WRITE_EXTERNAL_STORAGE) {
          requestedPermissions.push(PermissionsAndroid.PERMISSIONS.WRITE_EXTERNAL_STORAGE);
        }
      }

      const granted = await PermissionsAndroid.requestMultiple(requestedPermissions);

      const cameraGranted =
        granted['android.permission.CAMERA'] ===
        PermissionsAndroid.RESULTS.GRANTED;

      const readGranted = isTiramisuOrNewer
        ? granted['android.permission.READ_MEDIA_IMAGES'] === PermissionsAndroid.RESULTS.GRANTED ||
          granted['android.permission.READ_MEDIA_IMAGES'] === undefined
        : granted['android.permission.READ_EXTERNAL_STORAGE'] === PermissionsAndroid.RESULTS.GRANTED ||
          granted['android.permission.READ_EXTERNAL_STORAGE'] === undefined;

      const writeGranted = isTiramisuOrNewer
        ? true // WRITE_EXTERNAL_STORAGE is deprecated on API 33+
        : granted['android.permission.WRITE_EXTERNAL_STORAGE'] === PermissionsAndroid.RESULTS.GRANTED ||
          granted['android.permission.WRITE_EXTERNAL_STORAGE'] === undefined;

      if (cameraGranted && readGranted && writeGranted) {
        this.setState({ permissionsAuthorized: true });
      } else {
        this.onPermissionsDenied();
      }
    } catch (err) {
      this.onPermissionsDenied();
    }
  }

  static defaultProps = {
    onPictureTaken: () => {},
    onProcessing: () => {},
  };

  sendOnPictureTakenEvent(event) {
    return this.props.onPictureTaken(event.nativeEvent);
  }

  sendOnRectanleDetectEvent(event) {
    if (!this.props.onRectangleDetect) {
      return null;
    }
    return this.props.onRectangleDetect(event.nativeEvent);
  }

  getImageQuality() {
    if (!this.props.quality) {
      return 0.8;
    }
    if (this.props.quality > 1) {
      return 1;
    }
    if (this.props.quality < 0.1) {
      return 0.1;
    }
    return this.props.quality;
  }

  capture() {
    if (!this.state.permissionsAuthorized) {
      return Promise.reject(new Error('camera_permissions_not_granted'));
    }

    if (!CameraManager || typeof CameraManager.capture !== 'function') {
      return Promise.reject(new Error('capture_not_supported'));
    }

    const nodeHandle = this.nativeTag ?? findNodeHandle(this.nativeRef);

    if (!nodeHandle) {
      return Promise.reject(new Error('scanner_view_not_ready'));
    }

    try {
      const result = CameraManager.capture(nodeHandle);

      if (result && typeof result.then === 'function') {
        return result;
      }

      return result;
    } catch (error) {
      return Promise.reject(error);
    }
  }

  render() {
    if (!this.state.permissionsAuthorized) {
      return null;
    }
    const { onLayout, ...restProps } = this.props;
    return (
      <RNPdfScanner
        ref={(ref) => {
          this.nativeRef = ref;
          this.nativeTag = ref ? findNodeHandle(ref) : null;
        }}
        onLayout={(event) => {
          this.nativeTag = event?.nativeEvent?.target ?? this.nativeTag;
          if (onLayout) {
            onLayout(event);
          }
        }}
        {...restProps}
        onPictureTaken={this.sendOnPictureTakenEvent.bind(this)}
        onRectangleDetect={this.sendOnRectanleDetectEvent.bind(this)}
        useFrontCam={this.props.useFrontCam || false}
        brightness={this.props.brightness || 0}
        saturation={this.props.saturation || 1}
        contrast={this.props.contrast || 1}
        quality={this.getImageQuality()}
        detectionCountBeforeCapture={this.props.detectionCountBeforeCapture || 5}
        detectionRefreshRateInMS={this.props.detectionRefreshRateInMS || 50}
      />
    );
  }
}

PdfScanner.propTypes = {
  onPictureTaken: PropTypes.func,
  onRectangleDetect: PropTypes.func,
  overlayColor: PropTypes.oneOfType([PropTypes.number, PropTypes.string]),
  enableTorch: PropTypes.bool,
  useFrontCam: PropTypes.bool,
  saturation: PropTypes.number,
  brightness: PropTypes.number,
  contrast: PropTypes.number,
  detectionCountBeforeCapture: PropTypes.number,
  detectionRefreshRateInMS: PropTypes.number,
  quality: PropTypes.number,
  documentAnimation: PropTypes.bool,
  noGrayScale: PropTypes.bool,
  manualOnly: PropTypes.bool,
  ...View.propTypes,
};

export default PdfScanner;
