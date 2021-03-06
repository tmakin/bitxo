
use_bpm 90


use_transpose 12
use_debug false

i = 0

define :c5_3 do |c|
  return ring(c[0], c[2], c[0]+12, c[1]+12)
end


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


a.count.times do
  item = a[i]
  i += 1
  
  n = item[0]
  r = item[1]
  
  if(r.count == 3)
    r = ring(r[0], r[1], r[2], r[0] + 12)
  end
  
  puts i, r
  
  n.times do
    use_synth :square
    with_fx :slicer, phase: [0.125, 0.25, 0.5].choose do
      play r[0]-12, release: 3, amp: 1, cutoff: rrand(40, 80)
    end
    
    use_synth :beep
    4.times do
      play r.choose+12, release: 0.3
      play r, release: 0.3, amp: 0.5
      
      sleep 0.25
    end
  end
end






