#!/bin/zsh

set -e

REPO_DIR="${0:A:h}"
CONDA_ROOT="/opt/anaconda3"
ENV_PREFIX="${CONDA_ROOT}/envs/gazefollower"

if [[ ! -f "${CONDA_ROOT}/etc/profile.d/conda.sh" ]]; then
    echo "Conda was not found at ${CONDA_ROOT}."
    read -k 1 "?Press any key to close."
    exit 1
fi

if [[ ! -x "${ENV_PREFIX}/bin/python" ]]; then
    echo "The gazefollower environment is missing: ${ENV_PREFIX}"
    read -k 1 "?Press any key to close."
    exit 1
fi

source "${CONDA_ROOT}/etc/profile.d/conda.sh"
conda activate "${ENV_PREFIX}"
cd "${REPO_DIR}"

# MediaPipe imports Matplotlib. Keep its generated font/cache files local so
# first launch is faster and does not depend on global macOS cache permissions.
export MPLCONFIGDIR="${REPO_DIR}/.cache/matplotlib"
export XDG_CACHE_HOME="${REPO_DIR}/.cache"
mkdir -p "${MPLCONFIGDIR}" "${XDG_CACHE_HOME}"

python example/gui_launcher.py
