import json
import re

transcript_path = r'C:\Users\me\.gemini\antigravity\brain\e7ce19b2-e180-4276-8d85-d5fab562af56\.system_generated\logs\transcript.jsonl'

data = []
with open(transcript_path, 'r', encoding='utf-8') as f:
    for line in f:
        data.append(json.loads(line))

max_len = 0
best_text = ""

for step in data:
    text = step.get('output', step.get('content', ''))
    if 'VideoBackground.kt' in text and '1:' in text:
        m = re.search(r'modified to include a line number before every line.*?\n(1:.*?)\n(?:The above content shows|The above content does NOT)', text, re.DOTALL)
        if m:
            lines = m.group(1).split('\n')
            if len(lines) > max_len:
                max_len = len(lines)
                best_text = m.group(1)

print(f"Max lines found: {max_len}")
if best_text:
    output_lines = [l.split(': ', 1)[1] for l in best_text.split('\n') if re.match(r'^\d+: ', l)]
    print(f"Restored lines: {len(output_lines)}")
    with open(r'c:\Users\me\IdeaProject\StelLane\engine\src\main\kotlin\io\github\jwyoon1220\engine\VideoBackground.kt', 'w', encoding='utf-8') as f:
        f.write('\n'.join(output_lines))
