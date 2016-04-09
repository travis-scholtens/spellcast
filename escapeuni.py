from sys import stdin

for l in stdin.readlines():
  print('"{}",'.format(''.join([
      c if ord(c) < 128
        else '\\u{:x}'.format(ord(c))
      for c in l.rstrip()])))
