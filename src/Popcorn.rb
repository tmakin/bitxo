bpm = 300
use_bpm bpm
use_debug false
puts :d4 - :g3

intro = true
scratch = false
bass = false
drums = false
rand_cc = true
rand_cc_2 = false
done = false

cutoff = 90
cutoff_inc = 1
offset = 0


define :melody_player do |notes|
  count = notes.length
  i = 0
  
  notes.each  do |n|
    i += 1
    n += offset
    
    a2 = 0.5
    
    cutoff += cutoff_inc
    if(cutoff > 100)
      cutoff = 100
    elsif(cutoff < 30)
      cutoff = 30
    end
    
    offset2 = 0
    if(rand_cc)
      cutoff = rrand(60, 100)
      n2 = n + [-12,0, 12, 24].choose
      
      in_thread do
        
        play n2, attack: 0.0, release: 0.5, cutoff: cutoff
        sleep 0.5
        if rand_cc_2
          play n2, attack: 0.0, release: 0.5, cutoff: cutoff + 10
        end
      end
    end
    
    rate = rrand(0.9, 1.1)
    
    play n, attack: 0.0, release: 0.5, cutoff: cutoff
    
    # scratch
    if(scratch)
      sample :ambi_choir, rpitch: n-30, beat_stretch: rate, amp: 0.4
    end
    
    # bass
    if(bass && i % 2 == 1)
      sample :bd_haus, cutoff: 60, amp: 1.0
    end
    
    sleep 1
    
    if(i == count)
      play_pattern_timed [n, n], [a2,a2], attack: 0.0, release: a2, cutoff: cutoff
    end
  end
end

use_synth :square

part0 = [:g3,:g4,:d4,:g3,:g4,:d4,:g3,:g4,:d4,:g3,:bb3,:d4,:g4]
a = 0.5
t0 = [1,0.5,0.5,1,0.5,0.5,1,0.5,0.5,0.5,0.5,0.5,0.5]
n = play :g3, sustain: 8000, amp: 0, note_slide: 0.1, amp_slide: 8

introLevel = 0.1;

live_loop :intro_loop do
  sync "intro"
  print "intro"
  control n, amp: intro ? introLevel : 0
  if done
    introLevel -= 0.005 #Fade out
  end
  12.times do
    for i in 0..12
      control n, note: part0[i]+offset, cutoff: cutoff
      sleep t0[i]
    end
  end
end

live_loop :drum_loop do
  sync "drum_loop"
  sample :loop_tabla, beat_stretch: 64, amp: 4.0, finish: 0.5
  print "drums"
end

define :melody do |set_offset|
  offset = set_offset
  
  part1 = [:g3,:f3,:g3,:d3,:bb2,:d3,:g2]
  part2 = [:g3,:a3,:bb3,:a3,:bb3,:g3,:a3,:g3,:a3,:f3,:g3,:f3,:g3,:eb3,:g3]
  
  cue "intro"
  
  if drums
    cue "drum_loop"
  end
  print "part 1"
  melody_player part1
  
  print "part 2"
  melody_player part2
  
  print "Done"
end

define :set_state do |j|
  if(j == 1)
    bass = true
  elsif(j == 2)
    intro = false
    drums = false
  elsif(j == 3)
    scratch = true
    drums = true
  elsif(j == 4)
    drums = true
    intro = false
    rand_cc = true
  elsif(j == 5)
    drums = false
    rand_cc_2 = true
    bpm = 150
  elsif(j == 6)
    bpm = 300
    intro = true
    drums = true
    cutoff_inc=-1
  elsif(j == 7)
    rand_cc = false
    drums = false
    bass = false
  elsif(j == 8)
    done = true
  end
end

sleep 1
cue "intro"
sleep 32

j = 0
8.times do
  j+=1
  set_state j
  use_bpm bpm
  
  if done
    stop
  end
  
  with_fx :reverb, depth: 10, mix: 0.5 do
    melody 0
    if bpm == 300
      melody 5
      melody 0
    end
  end
end
