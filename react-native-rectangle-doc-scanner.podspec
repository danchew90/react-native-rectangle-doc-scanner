require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name         = 'react-native-rectangle-doc-scanner'
  s.version      = package['version']
  s.summary      = package.fetch('description', 'Document scanner with native camera overlay support for React Native.')
  s.homepage     = package['homepage'] || 'https://github.com/danchew90/react-native-rectangle-doc-scanner'
  s.license      = package['license'] || { :type => 'MIT' }
  s.author       = package['author'] || { 'react-native-rectangle-doc-scanner' => 'opensource@example.com' }
  s.source       = { :git => package.dig('repository', 'url') || s.homepage, :tag => "v#{s.version}" }

  s.platform     = :ios, '13.0'
  s.swift_version = '5.0'

  s.source_files = 'ios/**/*.{h,m,mm,swift}'
  s.public_header_files = 'ios/**/*.h'
  s.requires_arc = true

  s.dependency 'React-Core'
end
