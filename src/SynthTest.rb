use_bpm 120



define :test do
  s = scale :C4, :minor
  #s = s.concat(s.reverse.drop(1))
  
  puts s
  s.count.times do
    play s.tick, release: 3
    sleep 0.5
  end
  sleep 0.5
end

use_synth :growl
test

use_synth :dark_ambience
test

use_synth :hollow
test

use_synth_defaults amp: 0.2, detune: -12

use_synth :chiplead
test

use_synth :dpulse
test

use_synth :beep
test

use_synth :pluck
test


use_synth :growl
test
