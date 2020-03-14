
use_bpm 90

use_transpose 12
use_debug false

# Run this in another buffer to reset the counter
# set :i, 0

# Key: F# and C#
a = [
  [4, chord(:D2, :major)],             #:D2, :Fs2, :A2, :D3 # D
  
  [4, chord(:E2, :minor)],             #:E2, :G2,  :B2, :E3 # Em
  
  [4, chord(:A2, :minor, invert: -1)], #:E2, :A2,  :C3, :E3 # Am
  
  [4, chord(:D3, :major, invert: -2)], #:Fs2, :A2, :D3, :Fs3 # D
  
  [4, chord(:G2, :major)],             #:G2,  :B2,  :D3, :G3 # G
  
  [4, chord(:A2, 7)],                  #:A2,  :Cs3, :E3, :G3 # A7
  
  [4, chord(:D3, :major, invert: -2)],
  
  [4, chord(:A2, :major)],             #:A2,  :Cs3, :E3, :A3 # A
  
  [4, chord(:B2, :minor)],             # :B2,  :D3,  :Fs3,:B3
  
  [4, chord(:E3, :minor, invert: -1)], # :B2,  :E3,  :G3, :B3
  
  [4, ring(:A2,  :E3,  :A3, :Cs4)],    # A+11 ???
  
  [4, chord(:D3, :major)],
  
  [4, chord(:Gs3, :dim, invert: -2)],  # :B2,  :D3,  :Gs3,:B3 G#dim
  
  [4, ring(:A2,  :E3,  :A3, :Cs4)],    # A+11
  
  [2, ring(:E2,  :B2,  :E3, :A3)], # Esus4+11
  [2, ring(:E2,  :B2,  :E3, :G3)], # ???
  
  [2, ring(:E2,  :Cs3,  :E3, :G3)], # ???
  [1, ring(:E2,  :Fs2,  :Cs3, :Fs3)], # ???
  [1, ring(:E2,  :Fs2,  :Cs3, :E3)], # ???
  
  [4, ring(:D2, :Fs2, :B2, :D3)],
  
  [2, ring(:A2,  :D3,  :Fs3, :A3)], # ???
  [2, ring(:D2,  :Fs2,  :A2, :C3)], # ??
  
  [2, ring(:B1,  :D2,  :G2, :B2)], # ??
  [2, ring(:G2,  :B2,  :E3, :G3)], # ??
  
  [2, chord(:A2, 7)],
  [2, chord(:D3, :major, invert: -2)],
  
  [2, ring(:A1,  :E2,  :D3, :E3)], # ??
  [2, ring(:A1,  :E2,  :Cs3, :E3)], # ??
]

i = get[:i]
if(!i)
  i = 0
  set :i, i
end

live_loop :main do
  if(i >= a.count)
    i = 0
  end
  
  # live controls
  drums = true
  mid   = true
  bass  = true
  
  item = a[i]
  i+=1
  set :i, i
  
  n = item[0]
  r = item[1]
  
  if(r.count == 3)
    r = ring(r[0], r[1], r[2], r[0] + 12)
  end
  
  puts i, r
  
  # Drums
  # TODO: use cues to make the always play the whole bar
  finish = 0.5*n/4
  if drums
    with_fx :slicer, phase: [0.125, 0.25, 0.5, 1, 2].choose, mix: 1 do
      sample :loop_amen_full, beat_stretch: 8, amp: 2, finish: finish
    end
  end
  
  t = n*0.5
  
  bass_cutoff = [60, 110, 120].choose
  bass_cutoff_final = 40
  bass_cutof_inc = [5, 10, 20].choose
  
  if(bass_cutoff > 100)
    bass_cutof_inc = -bass_cutof_inc
  end
  
  if bass
    bass = 0
    with_fx :reverb do
      use_synth :beep
      play r, amp: 2, release: t
      
      use_synth :prophet
      bass = play r[0]-24, sustain: n, amp: 0.7, cutoff: bass_cutoff, slide: n
      control bass, cutoff: bass_cutoff_final
    end
  end
  
  if mid
    use_synth :dpulse
    with_fx :slicer, phase: [0.25, 0.5, 1].choose, wave:0 do
      play r[0]-12, sustain: n, release: 0, amp: 2, cutoff: rrand(40, 80)
    end
    
    with_fx :slicer, phase: [0.25, 0.5, 1].choose, wave:1 do
      play r[1]-12, sustain: n, release: 0, amp: 2, cutoff: rrand(30, 70)
    end
  end
  
  n.times do
    4.times do
      use_synth :pluck
      play r.tick+12, release: 0.3, amp: 0.7
      
      use_synth :beep
      play r.choose+12, release: 1, amp: 0.5
      
      use_synth :beep
      play r, release: 0.2, amp: 0.6
      
      sleep 0.25
    end
  end
end






