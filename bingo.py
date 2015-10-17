values = {
  'a': 1,
  'b': 3,
  'c': 3,
  'd': 2,
  'e': 1,
  'f': 4,
  'g': 2,
  'h': 4,
  'i': 1,
  'j': 8,
  'k': 5,
  'l': 1,
  'm': 3,
  'n': 1,
  'o': 1,
  'p': 3,
  'q': 10,
  'r': 1,
  's': 1,
  't': 1,
  'u': 1,
  'v': 4,
  'w': 4,
  'x': 8,
  'y': 4,
  'z': 10
}

frequencies = {
  'a': 9,
  'b': 2,
  'c': 2,
  'd': 4,
  'e': 12,
  'f': 2,
  'g': 3,
  'h': 2,
  'i': 9,
  'j': 1,
  'k': 1,
  'l': 4,
  'm': 2,
  'n': 6,
  'o': 8,
  'p': 2,
  'q': 1,
  'r': 6,
  's': 4,
  't': 6,
  'u': 4,
  'v': 2,
  'w': 2,
  'x': 1,
  'y': 2,
  'z': 1
}

healths = {
  'a': 5,
  'b': 7,
  'c': 9,
  'd': 11,
  'e': 11,
  'f': 14,
  'g': 13,
  'h': 14,
  'i': 12,
  'j': 17,
  'k': 13,
  'l': 8,
  'm': 12,
  'n': 10,
  'o': 11,
  'p': 15,
  'q': 14,
  'r': 10,
  's': 16,
  't': 12,
  'u': 9,
  'v': 15,
  'w': 14,
  'x': 19,
  'y': 20,
  'z': 15
}

names = {
  'a': 'ant',
  'b': 'badger',
  'c': 'crawler',
  'd': 'demon',
  'e': 'elf',
  'f': 'fury',
  'g': 'goblin',
  'h': 'hag',
  'i': 'imp',
  'j': 'jabber',
  'k': 'koala',
  'l': 'lemur',
  'm': 'manticore',
  'n': '?',
  'o': '?',
  'p': '?',
  'q': '?',
  'r': '?',
  's': '?',
  't': '?',
  'u': '?',
  'v': '?',
  'w': '?',
  'x': '?',
  'y': '?',
  'z': '?'
}

colors = {
  'a': 'darkred',
  'b': 'brown',
  'c': 'yellow',
  'd': 'red',
  'e': 'green',
  'f': 'darkblue',
  'g': 'darkgreen',
  'h': 'darkgray',
  'i': 'lightblue',
  'j': 'darkbrown',
  'k': 'lightgray',
  'l': 'orange',
  'm': 'darkorange',
  'n': 'white',
  'o': 'white',
  'p': 'white',
  'q': 'white',
  'r': 'white',
  's': 'white',
  't': 'white',
  'u': 'white',
  'v': 'white',
  'w': 'white',
  'x': 'white',
  'y': 'white',
  'z': 'white'
}

abcs = 'abcdefghijklmnopqrstuvwxyz'

with open('foes.js', 'w') as f:
  f.write('var foeKinds = [\n')
  for glyph in abcs:
    f.write("    new FoeKind('{}', '{}', '{}', {}, {}, {}),\n".format(
            glyph, *[d[glyph] for d in (names, colors, healths, values, frequencies)]))
  f.write(']\n')

def dist(letters):
  total = sum([frequencies[l] for l in letters])
  return { l: frequencies[l]/total for l in letters }

depth_dist = { i+1: dist(abcs[0:i+1])
    for i in range(len(abcs)) }

depth_avg_value = { d: sum([values[l] * depth_dist[d][l]
                            for l in depth_dist[d]])
                    for d in depth_dist }

enemies_per_level = [None, 2, 4.5, 7.5, 11] + [15] * (30 - 4)

# 100 tiles in Scrabble => ~50 per player, or 7 sets of 7 tiles each game
# If being "good" means getting one bingo per game, and "expert" means three,
# then that translates to "bingo rates" of ~1/7 and 3/7, respectively
#`
# Assuming 10 enemies per level => bingo every 5 levels
# First possible bingo at 5 (acceded), so that one is thrown off (it's not at 2.5)
# But others should be more or less evenly distributed, so smear them out
levels_per_bingo = 7

def experience_after_level():
  total = 0
  for l in range(1, 30):
    old_total = total
    total += (enemies_per_level[l] *
              depth_avg_value[l if l in depth_avg_value else max(depth_avg_value.keys())])
    if (l >= levels_per_bingo):
      total += 50 / levels_per_bingo
    yield l, total

# How much damage do enemies do?
#
# If enemy has health eh, speed es, and does an average of ed damage per attack,
# and player has speed ps and does pd damage, then it will take
#   pt = eh/pd
# player turns to kill, during which it will take
#   et = pt * es/ps
# turns of its own, dealing a total of
#   d = et * ed
# damage to the player.
#
# So the player needs health
#   ph > eh * es * ed / ps / pd
# to survive the encounter.
#
# So all of these stats are interchangeable, don't need to vary all three.

depth_avg_health = { d: sum([healths[l] * depth_dist[d][l]
                             for l in depth_dist[d]])
                     for d in depth_dist }

# for variety, alternate between increasing average attack damage and player speed
def next_upgrade(target_damage_rate):
  def upgrade_speed(damage_rate, current_damage, current_speed):
    return current_damage, max(current_speed + 1, damage_rate/current_damage/target_damage_rate)
  def upgrade_damage(damage_rate, current_damage, current_speed):
    return max(current_damage + 0.1, damage_rate/current_speed/target_damage_rate), current_speed
  while True:
    yield upgrade_damage
    yield upgrade_speed

def stats_at_level():
  # these are constant
  damage_per_enemy_dealt_hit = 3
  enemy_speed = 100

  # these will get buffed by level gains
  damage_per_player_dealt_hit = 5
  player_speed = 75

  # player has 50 health, refilled each experience level, so try to sap 40 of it each level
  upgrade = next_upgrade(40)
  for l in range(1, 31):
    enemy_health = depth_avg_health[l if l in depth_avg_health else max(depth_avg_health.keys())]
    damage_to_player_rate = enemies_per_level[l] * enemy_health * enemy_speed * damage_per_enemy_dealt_hit
    damage_to_player = damage_to_player_rate / player_speed / damage_per_player_dealt_hit
    # yield the current stats at this level
    yield damage_per_player_dealt_hit, player_speed
    # upgrade to the next level
    damage_per_player_dealt_hit, player_speed = next(upgrade)(damage_to_player_rate, damage_per_player_dealt_hit, player_speed)

with open('experience.js', 'w') as f:
  stats = list(stats_at_level())
  from math import floor
  f.write('var experienceLevelDefinitions = [\n')
  for l, e in [(0, 0)] + sorted(dict(experience_after_level()).items()):
    damage, speed = stats[l]
    f.write('  {{ expThreshold: {}, meanDamage: {}, speed: {} }},\n'.format(floor(e), round(damage, 1), floor(speed)))
  f.write(']\n')
