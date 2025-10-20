#!/usr/bin/env node

const fs = require('fs');
const path = require('path');

// Find react-native-document-scanner (could be in parent due to hoisting)
function findPackage(packageName) {
  const locations = [
    path.join(__dirname, '..', 'node_modules', packageName), // Same level
    path.join(__dirname, '..', '..', packageName),           // Hoisted to parent
    path.join(__dirname, '..', '..', '..', packageName),     // Hoisted to root
  ];

  for (const location of locations) {
    if (fs.existsSync(location)) {
      return location;
    }
  }
  return null;
}

const SCANNER_PATH = findPackage('react-native-document-scanner');
const VENDOR_PATH = path.join(__dirname, '..', 'vendor', 'react-native-document-scanner');

// Check if react-native-document-scanner is installed
if (!SCANNER_PATH) {
  console.log('⚠️  react-native-document-scanner not found, skipping quality patches');
  process.exit(0);
}

console.log('📸 Applying camera quality optimizations...');

try {
  // Files to copy
  const filesToCopy = [
    'ios/IPDFCameraViewController.m',
    'ios/DocumentScannerView.m',
    'ios/RNPdfScannerManager.m',
    'ios/RNPdfScannerManager.h',
    'ios.js'
  ];

  let copiedCount = 0;

  for (const file of filesToCopy) {
    const sourcePath = path.join(VENDOR_PATH, file);
    const targetPath = path.join(SCANNER_PATH, file);

    if (fs.existsSync(sourcePath)) {
      // Backup original if not already backed up
      if (!fs.existsSync(targetPath + '.original')) {
        fs.copyFileSync(targetPath, targetPath + '.original');
      }

      // Copy optimized version
      fs.copyFileSync(sourcePath, targetPath);
      copiedCount++;
    }
  }

  if (copiedCount === filesToCopy.length) {
    console.log('✅ iOS camera quality optimizations applied!');
    console.log('   - Modern AVCapturePhotoOutput API');
    console.log('   - High quality JPEG compression (95%+)');
    console.log('   - Quality prioritization enabled');
  } else {
    console.log('⚠️  Some optimized files not found in vendor folder');
  }

  console.log('✨ Setup complete!');
} catch (error) {
  console.error('❌ Error applying optimizations:', error.message);
  process.exit(1);
}
