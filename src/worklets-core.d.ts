import type { DependencyList } from 'react';

declare module 'react-native-worklets-core' {
  export function useRunOnJS<T extends (...args: any[]) => any>(
    callback: T,
    deps: DependencyList
  ): (...args: Parameters<T>) => void;
}
