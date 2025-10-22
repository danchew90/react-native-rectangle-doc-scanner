require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name         = "react-native-rectangle-doc-scanner"
  s.version      = package['version']
  s.summary      = package['description']
  s.homepage     = package['homepage']
  s.license      = package['license']
  s.authors      = { package['author']['name'] || 'Your Name' => package['author']['email'] || 'your@email.com' }
  s.platforms    = { :ios => "11.0" }
  s.source       = { :git => package['repository']['url'], :tag => "v#{s.version}" }

  s.source_files = "ios/**/*.{h,m,mm,swift}"

  s.dependency "React-Core"

  # Additional dependencies based on package.json peerDependencies
  # These are commented out as they are peerDependencies, not direct dependencies
  # s.dependency "react-native-image-picker"
  # s.dependency "react-native-image-crop-picker"
  # s.dependency "react-native-svg"
end
