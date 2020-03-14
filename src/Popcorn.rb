use_bpm 300

puts :d4 - :g3

intro = true
scratch = false
bass = false
rand_cc = false

cutoff = 30
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
      play n2, attack: 0.0, release: 0.5, cutoff: cutoff
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
      if(rrand(0, 1) > 0.5)
        sample :bd_haus, cutoff: 60, amp: 1.0
      end
      
      play_pattern_timed [n, n], [a2,a2], attack: 0.0, release: a2, cutoff: cutoff
    end
  end
end

define :melody do |set_offset|
  offset = set_offset
  
  part1 = [:g3,:f3,:g3,:d3,:bb2,:d3,:g2]
  part2 = [:g3,:a3,:bb3,:a3,:bb3,:g3,:a3,:g3,:a3,:f3,:g3,:f3,:g3,:eb3,:g3]
  
  #sample :loop_amen, beat_stretch: 8*a
  melody_player part1
  
  #sample :loop_amen, beat_stretch: 8*a
  melody_player part2
end

define :set_state do |j|
  if(j == 2)
    bass = true
    intro = false
  elsif(j == 3)
    scratch = true
    intro = false
  elsif(j == 4)
    rand_cc = true
  elsif(j == 5)
    intro = true
  elsif(j == 6)
    rand_cc = false
    cutoff_inc=-1
  elsif(j == 7)
    bass = false
  end
end

use_synth :square

part0 = [:g3,:g4,:d4,:g3,:g4,:d4,:g3,:g4,:d4,:g3,:bb3,:d4,:g4]
a = 0.5
t0 = [1,0.5,0.5,1,0.5,0.5,1,0.5,0.5,0.5,0.5,0.5,0.5]
n = play :g3, sustain: 8000, amp: 0, note_slide: 0.1, amp_slide: 8

live_loop :intro_loop do
  control n, amp: intro ? 0.1 : 0
  for i in 0..12
    control n, note: part0[i]+offset, cutoff: cutoff
    sleep t0[i]
  end
  #stop
end

sleep 32
j = 0
live_loop :melody_loop do
  j+=1
  
  set_state j
  
  with_fx :reverb, depth: 10, mix: 0.5 do
    melody 0
    melody 5
    melody 0
  end
end
