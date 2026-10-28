"""DORA metrics from GitHub's deployment records for the "production" environment.

- Deployment frequency: successful production deployments per week.
- Lead time for changes: first commit of the merged PR -> successful deployment of that commit (median).
- Change failure rate: failed deployments / all deployments.
- Failed deployment recovery time: failed deployment -> next successful deployment (median).
- Rework rate (approximation): commits on main whose message starts with "revert" or "hotfix".
"""
import json
import statistics
import subprocess
import sys
from datetime import datetime, timedelta, timezone


def gh(path, paginate=True):
    args = ["gh", "api", path] + (["--paginate", "--slurp"] if paginate else [])
    data = json.loads(subprocess.run(args, check=True, capture_output=True, text=True).stdout)
    if paginate and data and isinstance(data[0], list):
        return [item for page in data for item in page]
    return data


def ts(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def fmt(delta):
    if delta is None:
        return "n/a"
    hours = delta.total_seconds() / 3600
    return f"{hours:.1f} h" if hours < 48 else f"{hours / 24:.1f} days"


def main(days):
    repo = subprocess.run(["gh", "repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner"],
                          check=True, capture_output=True, text=True).stdout.strip()
    since = datetime.now(timezone.utc) - timedelta(days=days)

    deployments = [d for d in gh(f"repos/{repo}/deployments?environment=production&per_page=100")
                   if ts(d["created_at"]) >= since]
    results = []
    for d in sorted(deployments, key=lambda d: d["created_at"]):
        statuses = gh(f"repos/{repo}/deployments/{d['id']}/statuses?per_page=10")
        final = next((s["state"] for s in statuses if s["state"] in ("success", "failure", "error")), None)
        if final:
            results.append((d, final == "success"))

    ok = [d for d, success in results if success]
    failed = [d for d, success in results if not success]

    lead_times = []
    for d in ok:
        pulls = gh(f"repos/{repo}/commits/{d['sha']}/pulls")
        if pulls:
            commits = gh(f"repos/{repo}/pulls/{pulls[0]['number']}/commits?per_page=100")
            first = min(ts(c["commit"]["author"]["date"]) for c in commits)
        else:
            first = ts(gh(f"repos/{repo}/commits/{d['sha']}", paginate=False)["commit"]["author"]["date"])
        lead_times.append(ts(d["created_at"]) - first)

    recoveries = []
    for d, success in results:
        if not success:
            nxt = next((o for o in ok if o["created_at"] > d["created_at"]), None)
            if nxt:
                recoveries.append(ts(nxt["created_at"]) - ts(d["created_at"]))

    commits = gh(f"repos/{repo}/commits?sha=main&since={since.isoformat()}&per_page=100")
    rework = [c for c in commits if c["commit"]["message"].lower().startswith(("revert", "hotfix"))]

    median = lambda xs: statistics.median(xs) if xs else None
    weeks = max(days / 7, 1)
    total = len(results)
    print(f"DORA for {repo} — last {days} days")
    print(f"  Deployment frequency : {len(ok) / weeks:.1f} / week ({len(ok)} successful)")
    print(f"  Lead time (median)   : {fmt(median(lead_times))}")
    print(f"  Change failure rate  : {(len(failed) / total * 100) if total else 0:.0f}% ({len(failed)}/{total})")
    print(f"  Recovery (median)    : {fmt(median(recoveries))}")
    print(f"  Rework commits       : {len(rework)} of {len(commits)} on main")


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 30)
