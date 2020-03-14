# Super simple drum and bass
use_bpm 100

live_loop :amen_break do
  p = [0.125, 0.25, 0.5].choose
  with_fx :slicer, phase: p, wave: 0, mix: rrand(0.7, 1) do
    r = [1, 1, -0.5, -1].choose
    sample :loop_amen, beat_stretch: 2, rate: r, amp: 1.5, finish: r.abs
  end
  sleep 2
end

dc = 30
inc = 0.5
live_loop :bass_drum do
  dc += inc
  
  if(dc > 70)
    inc = -2
  end
  if(dc < 20)
    inc = 1
  end
  
  sample :bd_haus, cutoff: dc, amp: 2.0
  sleep 0.5
end

live_loop :choir do
  offset = [0, 0, 12].choose
  p = ring(0, 0, 0, 1 + offset).tick
  with_fx :slicer, phase: [0.25, 0.5].choose, invert_wave: 0, wave: 2 do
    #sample :ambi_choir, rate: 0.5, rpitch: p, finish: 0.6
    #with_fx :gverb, mix: 0.5, room: 10, spread: 1 do
    sample :ambi_choir, rpitch: p, amp: 0.3
    sample :ambi_choir, rate: 0.5, rpitch: p, finish: 0.6, amp: 0.6
    # control s, rpitch: p+10
    #end
  end
  #sample :ambi_choir, compress: 0, rpitch: p
  sleep 2
end

c = 80

live_loop :landing do
  c+=5
  if(c > 120)
    c = 70
  end
  bass_line = (knit :e1, 3, [:c1, :c2, :c3, :c4].choose, 1)
  with_fx :slicer, phase: [0.25, 0.5].choose, invert_wave: 1, wave: 0 do
    s = synth :square, note: bass_line.tick, sustain: 4, cutoff: 60
    control s, cutoff_slide: 4, cutoff: c
  end
  sleep 4
end