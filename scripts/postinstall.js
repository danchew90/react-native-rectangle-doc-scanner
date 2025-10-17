#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

const SCANNER_PATH = path.join(__dirname, '..', 'node_modules', 'react-native-document-scanner');
const VENDOR_PATH = path.join(__dirname, '..', 'vendor', 'react-native-document-scanner');

// Check if react-native-document-scanner is installed
if (!fs.existsSync(SCANNER_PATH)) {
  console.log('⚠️  react-native-document-scanner not found, skipping quality patches');
  process.exit(0);
}

console.log('📸 Applying camera quality optimizations...');

try {
  // Copy optimized iOS file
  const iosFile = 'ios/IPDFCameraViewController.m';
  const sourcePath = path.join(VENDOR_PATH, iosFile);
  const targetPath = path.join(SCANNER_PATH, iosFile);

  if (fs.existsSync(sourcePath)) {
    // Backup original if not already backed up
    if (!fs.existsSync(targetPath + '.original')) {
      fs.copyFileSync(targetPath, targetPath + '.original');
    }

    // Copy optimized version
    fs.copyFileSync(sourcePath, targetPath);
    console.log('✅ iOS camera quality optimizations applied!');
  } else {
    console.log('⚠️  Optimized iOS file not found in vendor folder');
  }

  console.log('✨ Setup complete!');
} catch (error) {
  console.error('❌ Error applying optimizations:', error.message);
  process.exit(1);
}
