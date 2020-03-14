#use_bpm 100
use_debug false

use_random_seed 123

live_loop :garzul do
  sync "main/1"
  
  sample :loop_garzul
  use_synth :prophet
  play :c1, release: 8, cutoff: rrand(70, 130)
end

live_loop :snare do
  sync "drums/*"
  sleep rrand(0.24, 0.27)
  
  count = [1, 1, 2].choose
  
  count.times do
    sample :drum_snare_soft, amp: 2
    sleep 0.125
  end
end

live_loop :cowbell do
  sync "drums/1"
  
  rate = ring(1, -1)
  count = [1, 2, 3].choose
  
  #sample :drum_cowbell, amp: 0.2, rpitch: -12
  for i in 1..count
    t = rrand(0.1, 0.2)
    puts "cowbell", i, rate[i-1], t
    sample :drum_cowbell, amp: 0.2, rate: rate[i-1]
    sleep t
  end
end


live_loop :scale do
  sync "main/*"
  
  a = (scale :c1, :minor_pentatonic, num_octaves: 3)
  # loop_flag = get[:loop_flag]
  
  # puts loop_flag
  r = [0, 1, 2].choose
  puts "scale_mode", r
  
  cutoff = 60
  amp = 0.3
  use_synth :tb303
  32.times do
    if(r == 0)
      play a.tick, release: 0.1, cutoff: cutoff, amp: amp
    elsif(r == 1)
      play a.choose, release: 0.1, cutoff: cutoff, amp: amp
    end
    
    sleep 0.125
    cutoff+=2
  end
end

# Timing Threads
in_thread(name: :main_cues) do
  loop do
    cue "main/1"
    sleep 4
    cue "main/2"
    sleep 4
  end
end

in_thread(name: :drum_cues) do
  loop do
    cue "drums/1"
    sleep 0.5
    cue "drums/2"
    sleep 0.5
  end
end

in_thread(name: :tick_cues) do
  loop do
    cue "tick"
    sleep 0.125
  end
end
