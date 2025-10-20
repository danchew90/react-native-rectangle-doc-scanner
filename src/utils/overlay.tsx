import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Animated, Easing, LayoutChangeEvent, StyleSheet, View } from 'react-native';

export interface ScannerOverlayProps {
  /** 활성화 시 스캔 바가 움직이며 자동 촬영 중임을 표시합니다. */
  active: boolean;
  color?: string;
  lineWidth?: number;
}

const BAR_THICKNESS = 4;

export const ScannerOverlay: React.FC<ScannerOverlayProps> = ({
  active,
  color = 'rgba(255,255,255,0.8)',
  lineWidth = StyleSheet.hairlineWidth,
}) => {
  const animatedValue = useRef(new Animated.Value(0)).current;
  const loopRef = useRef<Animated.CompositeAnimation | null>(null);
  const [frameHeight, setFrameHeight] = useState(0);

  const borderStyle = useMemo(
    () => ({
      borderColor: color,
      borderWidth: lineWidth,
    }),
    [color, lineWidth],
  );

  useEffect(() => {
    loopRef.current?.stop();
    if (!active || frameHeight <= 0) {
      animatedValue.stopAnimation();
      animatedValue.setValue(0);
      return;
    }

    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(animatedValue, {
          toValue: 1,
          duration: 1600,
          easing: Easing.inOut(Easing.quad),
          useNativeDriver: true,
        }),
        Animated.timing(animatedValue, {
          toValue: 0,
          duration: 1600,
          easing: Easing.inOut(Easing.quad),
          useNativeDriver: true,
        }),
      ]),
    );

    loopRef.current = loop;
    loop.start();

    return () => {
      loop.stop();
    };
  }, [active, animatedValue, frameHeight]);

  const handleLayout = (event: LayoutChangeEvent) => {
    const { height } = event.nativeEvent.layout;
    setFrameHeight(height);
  };

  const translateY =
    frameHeight <= BAR_THICKNESS
      ? 0
      : animatedValue.interpolate({
          inputRange: [0, 1],
          outputRange: [0, frameHeight - BAR_THICKNESS],
        });

  return (
    <View pointerEvents="none" style={StyleSheet.absoluteFill}>
      <View onLayout={handleLayout} style={[styles.frame, borderStyle]}>
        <View
          style={[
            styles.horizontalLine,
            { top: '33%', borderBottomColor: color, borderBottomWidth: lineWidth },
          ]}
        />
        <View
          style={[
            styles.horizontalLine,
            { top: '66%', borderBottomColor: color, borderBottomWidth: lineWidth },
          ]}
        />
        <View
          style={[
            styles.verticalLine,
            { left: '33%', borderRightColor: color, borderRightWidth: lineWidth },
          ]}
        />
        <View
          style={[
            styles.verticalLine,
            { left: '66%', borderRightColor: color, borderRightWidth: lineWidth },
          ]}
        />
        <Animated.View
          style={[
            styles.scanBar,
            {
              opacity: active ? 1 : 0,
              backgroundColor: color,
              transform: [{ translateY }],
            },
          ]}
        />
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  frame: {
    flex: 1,
    borderRadius: 12,
    overflow: 'hidden',
    backgroundColor: 'transparent',
  },
  horizontalLine: {
    position: 'absolute',
    right: 0,
    left: 0,
    height: 0,
  },
  verticalLine: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    width: 0,
  },
  scanBar: {
    position: 'absolute',
    left: 0,
    right: 0,
    height: BAR_THICKNESS,
  },
});
