from sys import argv

values = {
  '.': 0,
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
  '.': 2,
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
  'f': 24,
  'g': 23,
  'h': 34,
  'i': 32,
  'j': 42,
  'k': 38,
  'l': 28,
  'm': 48,
  'n': 45,
  'o': 40,
  'p': 50,
  'q': 56,
  'r': 48,
  's': 50,
  't': 57,
  'u': 62,
  'v': 69,
  'w': 75,
  'x': 78,
  'y': 90,
  'z': 105
}

speeds = {
  'a': 100,
  'b': 104,
  'c': 108,
  'd': 112,
  'e': 116,
  'f': 120,
  'g': 124,
  'h': 128,
  'i': 132,
  'j': 136,
  'k': 140,
  'l': 144,
  'm': 148,
  'n': 152,
  'o': 156,
  'p': 160,
  'q': 164,
  'r': 168,
  's': 172,
  't': 176,
  'u': 180,
  'v': 184,
  'w': 188,
  'x': 192,
  'y': 196,
  'z': 200
}

names = {
  'a': 'ant',
  'b': 'bogle',
  'c': 'crawler',
  'd': 'demon',
  'e': 'echidna',
  'f': 'faun',
  'g': 'goblin',
  'h': 'harpy',
  'i': 'imp',
  'j': 'jird',
  'k': 'kraken',
  'l': 'lich',
  'm': 'medusa',
  'n': 'nymph',
  'o': 'orc',
  'p': 'python',
  'q': 'quokka',
  'r': 'redcap',
  's': 'sylph',
  't': 'troll',
  'u': 'unicorn',
  'v': 'valkyr',
  'w': 'wendigo',
  'x': 'xenopus',
  'y': 'yeti',
  'z': 'zombie'
}

colors = {
  '.': 'gray',
  'a': 'darkred',
  'b': 'brown',
  'c': 'yellow',
  'd': 'crimson',
  'e': 'lawngreen',
  'f': 'cadetblue',
  'g': 'olive',
  'h': 'orange',
  'i': 'blue',
  'j': 'darkgoldenrod',
  'k': 'darkseagreen',
  'l': 'magenta',
  'm': 'darkorange',
  'n': 'orchid',
  'o': 'indianred',
  'p': 'saddlebrown',
  'q': 'lightcoral',
  'r': 'red',
  's': 'cyan',
  't': 'orangered',
  'u': 'white',
  'v': 'hotpink',
  'w': 'springgreen',
  'x': 'lemonchiffon',
  'y': 'peru',
  'z': 'darkgray'
}

abcs = 'abcdefghijklmnopqrstuvwxyz'

with open('foes.js', 'w') as f:
  f.write('var foeKinds = [\n')
  for glyph in abcs:
    f.write("    new FoeKind('{}', '{}', '{}', {}, {}, {}, {}),\n".format(
            glyph, *[d[glyph] for d in (names, colors, healths, speeds, values, frequencies)]))
  f.write(']\n')
  f.write("var shapeshifter = new FoeKind('.', 'metamorph', 'gray', undefined, undefined, {}, {});\n".format(
          *[d['.'] for d in (values, frequencies)]))

def dist(letters):
  total = sum([frequencies[l] for l in letters])
  return { l: frequencies[l]/total for l in letters }

depth_dist = { i+1: dist(abcs[0:i+1] + ('.' if i > 0 else ''))
    for i in range(len(abcs)) }

depth_avg_value = { d: sum([values[l] * depth_dist[d][l]
                            for l in depth_dist[d]])
                    for d in depth_dist }

enemies_per_level = [None, 2, 4.5, 7.5, 11] + [15 + 2*i for i in range(35)]

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
  for l in range(1, 31):
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

depth_avg_health = { d: sum([(healths[l] if l in healths else max(healths.values())) * depth_dist[d][l]
                             for l in depth_dist[d]])
                     for d in depth_dist }

depth_avg_speed = { d: sum([(speeds[l] if l in speeds else max(speeds.values())) * depth_dist[d][l]
                            for l in depth_dist[d]])
                    for d in depth_dist }

# for variety, alternate between increasing average attack damage and player speed
def next_upgrade(target_damage_rate):
  def upgrade_speed(damage_rate, current_damage, current_speed):
    return current_damage, current_speed + 8
  def upgrade_damage(damage_rate, current_damage, current_speed):
    return max(current_damage + 0.1, damage_rate/current_speed/target_damage_rate), current_speed
  while True:
    yield upgrade_damage
    yield upgrade_speed

# these are constant
damage_per_enemy_dealt_hit = 3

def stats_at_level():
  # these will get buffed by level gains
  damage_per_player_dealt_hit = 5
  player_speed = 75

  # player has 50 health, refilled each experience level; try to sap 125 of it each level
  upgrade = next_upgrade(125)
  for l in range(1, 32):
    enemy_health = depth_avg_health[l if l in depth_avg_health else max(depth_avg_health.keys())]
    enemy_speed = depth_avg_speed[l if l in depth_avg_speed else max(depth_avg_speed.keys())]
    damage_to_player_rate = enemies_per_level[l] * enemy_health * enemy_speed * damage_per_enemy_dealt_hit
    damage_to_player = damage_to_player_rate / player_speed / damage_per_player_dealt_hit
    # yield the current stats at this level
    yield damage_per_player_dealt_hit, player_speed
    # upgrade to the next level
    damage_per_player_dealt_hit, player_speed = next(upgrade)(damage_to_player_rate, damage_per_player_dealt_hit, player_speed)

def lifespan_by_depth_and_level():
  lifespans = []
  stats = list(stats_at_level())
  for d in range(1, 40):
    by_level = []
    for l in range(len(stats)):
      damage_per_player_dealt_hit, player_speed = stats[l]
      damage_to_player_rate = enemies_per_level[d] * depth_avg_health[d if d in depth_avg_health else max(depth_avg_health.keys())] * depth_avg_speed[l if l in depth_avg_speed else max(depth_avg_speed.keys())] * damage_per_enemy_dealt_hit
      damage_to_player = damage_to_player_rate / player_speed / damage_per_player_dealt_hit
      by_level.append(damage_to_player/50)
    lifespans.append(by_level)
  return lifespans

if len(argv) > 1 and argv[1] == '--chart':
  import matplotlib.pyplot as p
  lifespans = lifespan_by_depth_and_level()
  ps = []
  for i in range(len(lifespans[0])-1):
    ps.append(p.plot([d+1 for d in range(len(lifespans))],
                     [lifespans[d][i] for d in range(len(lifespans))])[0])
  p.legend(ps, [str(i+1) for i in range(len(lifespans[0])-1)])
  p.show()

with open('experience.js', 'w') as f:
  stats = list(stats_at_level())
  from math import floor
  f.write('var experienceLevelDefinitions = [\n')
  for l, e in [(0, 0)] + sorted(dict(experience_after_level()).items()):
    damage, speed = stats[l]
    f.write('  {{ expThreshold: {}, meanDamage: {}, speed: {} }},\n'.format(floor(e), round(damage, 1), floor(speed)))
  f.write(']\n')
