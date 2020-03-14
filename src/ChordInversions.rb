use_bpm 150

t = 0.5

play chord(:a, :major)
sleep t
i = 0
2.times do
  use_synth :beep
  play chord(:a, 7)
  sleep t
  play chord(:a, :major, invert: 1)
  sleep t
  play chord(:a, :major, invert: 2)
  sleep t
  play :a4+i*12
  play chord(:a+5, :major, invert: 1)
  sleep t
  i+=1
end
sleep t
play chord(:a-7, :major)
sleep 1.5*t
play chord(:a-7, :major)
sleep 0.5*t
play chord(:a-7, :major), release: 4



